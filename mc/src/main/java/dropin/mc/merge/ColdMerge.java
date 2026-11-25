package dropin.mc.merge;

import dropin.core.ledger.Deltas;
import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.merge.BranchStore;
import dropin.core.merge.Branches.Change;
import dropin.core.merge.ChunkContent;
import dropin.core.merge.MergePlanner;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.member.RecordApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The cold merge of DESIGN 4.10: the preserved branches a copy holds are merged into it by whoever starts the
 * world next, <b>before</b> the integrated server boots. The world is closed at that moment, so its chunks are
 * written with the game's own region-file classes opened standalone (as a member's copy is); nothing here may
 * run on a world that is open on this machine.
 */
public final class ColdMerge {
    private static final String K = "text.dropin_coop.";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * What one merge did.
     *
     * @param player        who played the merged session
     * @param branchHead    the branch's last checkpoint
     * @param entry         the merge entry appended to the ledger
     * @param chunksApplied chunks taken from the branch
     * @param chunksKept    chunks changed on both sides: the main world's stayed, the branch's are in {@code conflicts}
     * @param conflicts     the folder under {@code dropin/backups/conflicts/} with the branch's records, or null
     */
    public record Report(String player, String branchHead, LedgerEntry entry, int chunksApplied, int chunksKept,
                         int filesApplied, int filesKept, Path conflicts) {
        public Component message() {
            if (chunksKept + filesKept == 0) {
                return Component.translatable(K + "merge.report", player, chunksApplied);
            }
            return filesKept == 0
                    ? Component.translatable(K + "merge.report_kept", player, chunksApplied, chunksKept)
                    : Component.translatable(K + "merge.report_kept_files", player, chunksApplied, chunksKept, filesKept);
        }
    }

    private static final List<Report> REPORTS = new CopyOnWriteArrayList<>();
    private static volatile int shown;

    private ColdMerge() {
    }

    /** How many merge reports were put into the chat since the game started (for the self-tests). */
    public static int shown() {
        return shown;
    }

    /** Every merge this game ran since it started, oldest first (for the self-tests). */
    public static List<Report> reports() {
        return List.copyOf(REPORTS);
    }

    /**
     * Merges every pending branch of the closed copy in {@code folder}, then tells the player in chat once the
     * world is up. A failure is logged and leaves the remaining branches pending; the world still opens.
     * Not on the client thread.
     */
    public static void run(Minecraft mc, CoopWorld world, Path folder, String uuid, String name, String sess) {
        List<Report> done = new ArrayList<>();
        try {
            merge(world, folder, uuid, name, sess, done);
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Merging the offline sessions of '{}' failed; what is left stays pending", world.name(), e);
        }
        if (done.isEmpty()) {
            return;
        }
        REPORTS.addAll(done);
        Thread thread = new Thread(() -> {
            try {
                for (int waited = 0; waited < 300_000 && (mc.player == null || mc.getSingleplayerServer() == null); waited += 250) {
                    Thread.sleep(250);
                }
                mc.execute(() -> {
                    if (mc.player != null) {
                        for (Report report : done) {
                            mc.gui.getChat().addMessage(report.message());
                            shown++;
                        }
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dropin-coop-merge-report");
        thread.setDaemon(true);
        thread.start();
    }

    private static void merge(CoopWorld world, Path folder, String uuid, String name, String sess, List<Report> done) throws IOException {
        Path dropin = folder.resolve(CoopWorldStore.MARKER_DIR);
        BranchStore store = new BranchStore(dropin.resolve("branches"));
        List<String> heads = store.heads();
        Path manifestFile = dropin.resolve("manifest").resolve("current.tsv");
        if (heads.isEmpty() || !Files.isRegularFile(manifestFile)) {
            return;
        }
        Ledger ledger = Ledger.load(world.keys().auth(), dropin.resolve("ledger.jsonl"));
        if (ledger.size() == 0) {
            return;
        }
        List<BranchStore.Branch> pending = new ArrayList<>();
        for (String head : heads) {
            if (ledger.hasMerged(head) || ledger.contains(head)) {
                // Merged by someone else already, or part of this copy's own lineage.
                store.delete(head);
                continue;
            }
            BranchStore.Branch branch;
            try {
                branch = store.load(head);
            } catch (IOException e) {
                DropIn.LOGGER.warn("A preserved offline session of '{}' cannot be read and is skipped: {}", world.name(), e.toString());
                continue;
            }
            if (!branch.entry().verify(world.keys().auth()) || !branch.entry().hash().equals(head)) {
                DropIn.LOGGER.warn("A preserved offline session of '{}' does not verify and is skipped", world.name());
                continue;
            }
            if (branch.ancestor().isEmpty() || !ledger.contains(branch.ancestor())) {
                DropIn.LOGGER.warn("The offline session of {} left '{}' at a checkpoint this copy does not have; it stays pending",
                        branch.entry().name(), world.name());
                continue;
            }
            pending.add(branch);
        }
        if (pending.isEmpty()) {
            return;
        }
        pending.sort(Comparator.comparingLong(branch -> branch.entry().ts()));
        Manifest manifest = Manifest.load(manifestFile);
        // Records reach the world before the manifest and the ledger are saved. Should the merge stop in between
        // (a crash, a record the game cannot write), this marker makes the next host compare every record with
        // the manifest (HostSync.takeRescan), so what did reach the world is still sent to everyone. A marker a
        // restore left (DESIGN 4.16) is not ours to remove.
        Path rescan = dropin.resolve("rescan");
        boolean hadRescan = Files.exists(rescan);
        if (!hadRescan) {
            Files.writeString(rescan, "");
        }
        for (BranchStore.Branch branch : pending) {
            done.add(mergeOne(world, folder, dropin, store, ledger, manifest, manifestFile, branch, uuid, name, sess));
        }
        if (!hadRescan) {
            Files.deleteIfExists(rescan);
        }
    }

    private static Report mergeOne(CoopWorld world, Path folder, Path dropin, BranchStore store, Ledger ledger, Manifest manifest,
                                   Path manifestFile, BranchStore.Branch branch, String uuid, String name, String sess) throws IOException {
        MergePlanner.Plan plan;
        String label = LocalDateTime.now().format(STAMP) + "-" + safeName(branch.entry().name());
        Path backups = dropin.resolve("backups");
        Path conflicts = backups.resolve("conflicts").resolve(label);
        Path replaced = backups.resolve("merged").resolve(label);
        Map<String, String> changed = new LinkedHashMap<>();
        Map<String, String> base = new LinkedHashMap<>();
        Map<String, String> contentBase = new LinkedHashMap<>();
        Path contentFile = dropin.resolve("manifest").resolve("content.tsv");
        Manifest content = Manifest.load(contentFile);
        try (RecordApplier applier = new RecordApplier(folder)) {
            // A chunk the game only saved again (new LastUpdate, InhabitedTime) is unchanged for the merge.
            plan = MergePlanner.plan(branch.keys(), manifest::get, key -> {
                try {
                    return ChunkContent.hash(key, applier.read(key));
                } catch (IOException | RuntimeException e) {
                    return manifest.get(key);
                }
            }, branch.entry().host());
            // Every record this merge needs must be there and whole before the world is touched: a damaged
            // branch then fails here, leaving the world exactly as it was, instead of half way through.
            for (String key : plan.apply()) {
                String hash = branch.keys().get(key).hash();
                if (!hash.isEmpty()) {
                    store.record(branch.head(), hash);
                }
            }
            for (String key : plan.conflicts()) {
                // The main world's record stays; the branch's is kept for manual recovery.
                Change change = branch.keys().get(key);
                saveCopy(conflicts, key, change.hash().isEmpty() ? null : store.record(branch.head(), change.hash()));
            }
            for (String key : plan.apply()) {
                Change change = branch.keys().get(key);
                byte[] old;
                try {
                    old = applier.read(key);
                } catch (IOException | RuntimeException e) {
                    old = null;
                }
                if (old != null) {
                    saveCopy(replaced, key, old);
                }
                if (ChunkContent.applies(key)) {
                    contentBase.put(key, ChunkContent.hash(key, old));
                }
                if (change.hash().isEmpty()) {
                    applier.delete(key);
                    manifest.remove(key);
                    content.remove(key);
                } else {
                    byte[] record = store.record(branch.head(), change.hash());
                    applier.apply(key, record);
                    manifest.put(key, change.hash());
                    if (ChunkContent.applies(key)) {
                        content.put(key, ChunkContent.hash(key, record));
                    }
                }
                changed.put(key, change.hash());
                base.put(key, change.base());
            }
            applier.flush();
        }
        LedgerEntry entry = LedgerEntry.createMerge(world.keys().auth(), ledger.headSeq() + 1, ledger.head(), branch.head(),
                uuid, name, sess, System.currentTimeMillis() / 1000, manifest.root(), changed.size());
        Deltas.write(dropin.resolve("deltas"), entry.seq(), changed, base, contentBase);
        content.save(contentFile);
        manifest.save(manifestFile);
        ledger.append(entry);
        int size = manifest.size();
        new SyncState(entry.hash(), entry.seq(), true, size, size).save(dropin.resolve("state.json"));
        store.delete(branch.head());
        DropIn.LOGGER.info("Merged the offline session of {} into '{}' as checkpoint {}: {} chunks and {} files applied, {} chunks and "
                        + "{} files kept from the main world{}, root {}", branch.entry().name(), world.name(), entry.seq(),
                plan.chunksApplied(), plan.filesApplied(), plan.chunksKept(), plan.filesKept(),
                plan.conflicts().isEmpty() ? "" : " (the other side's records are in " + conflicts + ")", entry.root());
        return new Report(branch.entry().name(), branch.head(), entry, plan.chunksApplied(), plan.chunksKept(),
                plan.filesApplied(), plan.filesKept(), plan.conflicts().isEmpty() ? null : conflicts);
    }

    /**
     * Where the copy of a record goes inside a backup folder: {@code files/<path>} for a file record,
     * {@code chunks/<dimension>/<kind>/c.<cx>.<cz>.nbt} (uncompressed NBT) for a chunk record.
     */
    public static Path backupPath(Path dir, String key) throws IOException {
        Path file;
        if (RecordKey.isChunk(key)) {
            RecordKey.Chunk chunk = RecordKey.parseChunk(key);
            file = dir.resolve("chunks").resolve(chunk.dim().replace(':', '/')).resolve(chunk.kind())
                    .resolve("c." + chunk.cx() + "." + chunk.cz() + ".nbt");
        } else if (RecordKey.isFile(key) && RecordKey.fileAllowed(RecordKey.filePath(key))) {
            file = dir.resolve("files").resolve(RecordKey.filePath(key));
        } else {
            throw new IOException("Refused record " + key);
        }
        if (!file.normalize().startsWith(dir.normalize())) {
            throw new IOException("Refused record " + key);
        }
        return file;
    }

    /** @param bytes null for "the other side deleted this record": noted in {@code deleted.txt} */
    private static void saveCopy(Path dir, String key, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        if (bytes == null) {
            Files.writeString(dir.resolve("deleted.txt"), key + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return;
        }
        Path file = backupPath(dir, key);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9 _.()'-]", "_").trim();
        return safe.isEmpty() || safe.startsWith(".") ? "player" : safe;
    }

    /**
     * DESIGN 4.10, "too old to merge": writes a copy that cannot be merged any more as an ordinary world of its
     * own, "&lt;name&gt; (Jo's branch)", with no link to the co-op world. The copy is not open (its owner plays
     * as a remote client); it is read through the sync's own storage.
     *
     * @return the new world's folder name under saves/
     */
    public static String saveSeparate(Minecraft mc, CoopWorld world, Manifest manifest, RecordApplier copy, String player) throws IOException {
        String display = world.name() + " (" + player + "'s branch)";
        String base = safeName(display);
        String folderName = base;
        for (int n = 2; Files.exists(mc.getLevelSource().getLevelPath(folderName)); n++) {
            folderName = base + " " + n;
        }
        Path target = mc.getLevelSource().getLevelPath(folderName);
        Files.createDirectories(target);
        try (RecordApplier files = new RecordApplier(target)) {
            for (String key : manifest.keys()) {
                byte[] bytes = copy.read(key);
                if (bytes != null) {
                    files.apply(key, bytes);
                }
            }
            files.flush();
        }
        Path level = target.resolve("level.dat");
        if (Files.isRegularFile(level)) {
            try {
                CompoundTag root = NbtIo.readCompressed(level, NbtAccounter.unlimitedHeap());
                root.getCompound("Data").putString("LevelName", display);
                NbtIo.writeCompressed(root, level);
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.warn("Could not name the separate world '{}'; it keeps the name of '{}'", folderName, world.name(), e);
            }
        }
        return folderName;
    }
}
