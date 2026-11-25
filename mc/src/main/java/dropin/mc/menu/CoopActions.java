package dropin.mc.menu;

import dropin.core.invite.Invite;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.core.world.ImportCheck;
import dropin.core.world.WorldArchive;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.HostSession;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What the co-op menu does, without any widgets (DESIGN 4.4, 4.13), so the screens and the self-tests
 * run the same code.
 */
public final class CoopActions {
    private static final String K = "text.dropin_coop.";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Set while the vanilla Create World screen was opened from "New": the next new world becomes co-op. */
    public static volatile boolean pendingNew;

    /** @param key the translation key of {@code message}, for the self-tests */
    public record Outcome(boolean ok, String key, Component message, CoopWorld world) {
        static Outcome ok(CoopWorld world, String key, Object... args) {
            return new Outcome(true, K + key, Component.translatable(K + key, args), world);
        }

        static Outcome fail(String key, Object... args) {
            return new Outcome(false, K + key, Component.translatable(K + key, args), null);
        }
    }

    private CoopActions() {
    }

    // ---- lookups -----------------------------------------------------------------------------

    /** world id -> folder name under saves/, for every folder marked as a co-op world. */
    public static Map<String, String> folders(Minecraft mc) {
        Map<String, String> result = new TreeMap<>();
        Path saves = mc.getLevelSource().getBaseDir();
        if (!Files.isDirectory(saves)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(saves)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                CoopWorldStore.readMarker(dir).ifPresent(id -> result.putIfAbsent(id, dir.getFileName().toString()));
            }
        } catch (IOException e) {
            DropIn.LOGGER.warn("Could not list the saves folder", e);
        }
        return result;
    }

    /** True if the save folder is a co-op world on this PC (badge in the Singleplayer list). */
    public static boolean isCoopLevel(Minecraft mc, String levelId) {
        try {
            return CoopWorlds.forFolder(mc.getLevelSource().getLevelPath(levelId)).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- import ------------------------------------------------------------------------------

    /** Import from saves (DESIGN 4.4): the three checks in order, the optional backup, then the key files. */
    public static Outcome importFromSaves(Minecraft mc, String levelId, boolean backup) {
        LevelStorageSource source = mc.getLevelSource();
        Path folder = source.getLevelPath(levelId);
        if (!Files.isRegularFile(folder.resolve("level.dat"))) {
            return Outcome.fail("import.no_level_dat", levelId);
        }
        // Check 1 first: the summary of a world this game has open cannot always be read.
        if (isOpenHere(mc, folder)) {
            return Outcome.fail("import.open", levelId);
        }
        LevelSummary summary;
        try {
            List<LevelSummary> summaries = source.loadLevelSummaries(
                    new LevelStorageSource.LevelCandidates(List.of(new LevelStorageSource.LevelDirectory(folder))))
                    .get(60, TimeUnit.SECONDS);
            summary = summaries.isEmpty() ? null : summaries.get(0);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not read world {}", levelId, e);
            summary = null;
        }
        if (summary == null || summary.levelVersion() == null) {
            return Outcome.fail("import.unreadable", levelId);
        }

        Optional<String> marker = CoopWorldStore.readMarker(folder);
        boolean keyKnown = false;
        if (marker.isPresent()) {
            try {
                keyKnown = CoopWorlds.store().get(marker.get()).isPresent();
            } catch (IOException | RuntimeException e) {
                keyKnown = false;
            }
        }
        boolean open = summary.isLocked() || isOpenHere(mc, folder);
        ImportCheck.Result check = ImportCheck.check(open, marker.isPresent(), keyKnown,
                summary.levelVersion().minecraftVersion().getVersion(),
                SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        switch (check) {
            case OPEN:
                return Outcome.fail("import.open", summary.getLevelName());
            case ALREADY_COOP:
                return Outcome.fail("import.already_coop", summary.getLevelName());
            case NEEDS_INVITE:
                return Outcome.fail("import.needs_invite", summary.getLevelName());
            case TOO_OLD:
                return Outcome.fail("import.too_old", summary.getLevelName());
            case TOO_NEW:
                return Outcome.fail("import.too_new", summary.getLevelName());
            default:
                break;
        }

        if (backup) {
            try (LevelStorageSource.LevelStorageAccess access = source.createAccess(levelId)) {
                access.makeWorldBackup();
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.warn("Backup of {} failed", levelId, e);
                return Outcome.fail("import.backup_failed", summary.getLevelName());
            }
        }
        try {
            CoopWorld world = CoopWorlds.store().importWorld(folder, summary.getLevelName(),
                    mc.getUser().getProfileId().toString(), mc.getUser().getName(), now(), RANDOM);
            DropIn.LOGGER.info("Imported '{}' as co-op world {}", world.name(), world.worldId());
            return Outcome.ok(world, "import.done", world.name());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Import of {} failed", levelId, e);
            return Outcome.fail("import.failed", summary.getLevelName());
        }
    }

    /** Import of files dropped on a screen: each folder or .zip is brought into saves/ first, then imported. */
    public static Outcome importDropped(Minecraft mc, List<Path> files) {
        Outcome last = Outcome.fail("import.nothing_dropped");
        for (Path file : files) {
            last = importDropped(mc, file);
            if (!last.ok()) {
                return last;
            }
        }
        return last;
    }

    private static Outcome importDropped(Minecraft mc, Path file) {
        Path saves = mc.getLevelSource().getBaseDir();
        String shown = file.getFileName().toString();
        Path folder;
        try {
            Files.createDirectories(saves);
            if (Files.isDirectory(file)) {
                if (file.toAbsolutePath().normalize().startsWith(saves.toAbsolutePath().normalize())) {
                    return importFromSaves(mc, shown, true);
                }
                folder = WorldArchive.copyFolder(file, saves);
            } else if (shown.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                folder = WorldArchive.extractZip(file, saves);
            } else {
                return Outcome.fail("import.not_a_world", shown);
            }
        } catch (WorldArchive.WorldImportException e) {
            DropIn.LOGGER.warn("Refused to import {}: {}", shown, e.getMessage());
            return switch (e.reason()) {
                case PATH_ESCAPE -> Outcome.fail("import.zip_escape", shown);
                case NO_LEVEL_DAT -> Outcome.fail("import.no_level_dat", shown);
                case UNREADABLE -> Outcome.fail("import.not_a_world", shown);
            };
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not copy {} into saves", shown, e);
            return Outcome.fail("import.copy_failed", shown);
        }

        // A copy of a co-op world this PC already has the key for (sent by a friend) only needs to be listed.
        Optional<String> marker = CoopWorldStore.readMarker(folder);
        if (marker.isPresent() && !folders(mc).getOrDefault(marker.get(), "").isEmpty()
                && folders(mc).get(marker.get()).equals(folder.getFileName().toString())) {
            try {
                Optional<CoopWorld> known = CoopWorlds.store().get(marker.get());
                if (known.isPresent()) {
                    return Outcome.ok(known.get(), "import.copy_attached", known.get().name());
                }
            } catch (IOException | RuntimeException e) {
                // fall through to the ordinary checks
            }
        }
        // The copy in saves/ is new, so the original is the backup.
        return importFromSaves(mc, folder.getFileName().toString(), false);
    }

    private static boolean isOpenHere(Minecraft mc, Path folder) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        try {
            return Files.isSameFile(server.getWorldPath(LevelResource.ROOT), folder);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- new world, make co-op ---------------------------------------------------------------

    /**
     * Called before an integrated server loads its level. If the world was created from "New" on the
     * Co-op Worlds screen, it is registered as a co-op world before its first load (DESIGN 4.4).
     */
    public static void onServerStarting(MinecraftServer server) {
        if (!pendingNew) {
            return;
        }
        pendingNew = false;
        registerOpenWorld(Minecraft.getInstance(), server);
    }

    /** "Make co-op" in the pause menu: import in place of the world that is open. */
    public static boolean canMakeCoop(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        return server != null && !server.isPublished() && !CoopWorlds.isCoopServer(server);
    }

    public static Outcome makeCoop(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || !canMakeCoop(mc)) {
            return Outcome.fail("import.failed", "");
        }
        return registerOpenWorld(mc, server);
    }

    private static Outcome registerOpenWorld(Minecraft mc, MinecraftServer server) {
        Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = server.getWorldData().getLevelName();
        String uuid = mc.getUser().getProfileId().toString();
        String player = mc.getUser().getName();
        try {
            CoopWorld world = CoopWorlds.store().importWorld(folder, name, uuid, player, now(), RANDOM);
            CoopWorlds.markCoop(server);
            byte[] sess = new byte[8];
            RANDOM.nextBytes(sess);
            HostSession.start(mc, world, CoopWorlds.rendezvous(world), HexFormat.of().formatHex(sess), uuid, player);
            DropIn.LOGGER.info("'{}' is now co-op world {}", name, world.worldId());
            return Outcome.ok(world, "import.done", world.name());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not make '{}' a co-op world", name, e);
            return Outcome.fail("import.failed", name);
        }
    }

    // ---- invite, remove, owner key -----------------------------------------------------------

    /** The world an invite code names, not saved anywhere, or null if the text is not an invite. For the join preview. */
    public static CoopWorld previewInvite(String text) {
        try {
            Invite invite = Invite.decode(text.trim());
            java.util.HexFormat hex = java.util.HexFormat.of();
            String worldId = dropin.core.crypto.WorldKeys.fromSeed(invite.seed()).worldId();
            return new CoopWorld(worldId, hex.formatHex(invite.seed()), worldId.substring(0, 6),
                    hex.formatHex(invite.ownerFingerprint()), "", List.of());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Export world (the owner's wrench menu): the closed world as a zip in its backups folder, without the
     * co-op folder, so it opens anywhere as an ordinary world and can be imported again. Not the client thread.
     */
    public static Path exportWorld(Minecraft mc, CoopWorld world, Path folder) throws IOException {
        if (mc.getSingleplayerServer() != null) {
            throw new IOException("a world is open");
        }
        String name = world.name().replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path zip = dropin.mc.savepoint.SavePointActions.backupsDir(folder)
                .resolve((name.isEmpty() ? "world" : name) + "-world-" + stamp + ".zip");
        int files = dropin.core.world.WorldArchive.exportZip(folder, zip);
        DropIn.LOGGER.info("Exported co-op world '{}' to {}: {} files", world.name(), zip, files);
        return zip;
    }

    public static Outcome joinWithInvite(Minecraft mc, String text) {
        Invite invite;
        try {
            invite = Invite.decode(text.trim());
        } catch (RuntimeException e) {
            return Outcome.fail("invite.bad");
        }
        try {
            String worldId = dropin.core.crypto.WorldKeys.fromSeed(invite.seed()).worldId();
            String name = Component.translatable(K + "invite.default_name", worldId.substring(0, 6)).getString();
            CoopWorld world = CoopWorlds.store().joinWithInvite(invite, name);
            return Outcome.ok(world, "invite.joined", world.name());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not save the invited world", e);
            return Outcome.fail("invite.failed");
        }
    }

    /** Remove (DESIGN 4.4): deletes {@code dropin/} in the world folder and the local keys. The world stays. */
    public static Outcome remove(Minecraft mc, CoopWorld world) {
        try {
            String folder = folders(mc).get(world.worldId());
            CoopWorlds.store().remove(world.worldId(), folder == null ? null : mc.getLevelSource().getLevelPath(folder));
            return Outcome.ok(world, "remove.done", world.name());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not remove co-op world {}", world.worldId(), e);
            return Outcome.fail("remove.failed", world.name());
        }
    }

    /** "Export owner key" (DESIGN 4.13): a copy of the owner key file in the game folder. */
    public static Outcome exportOwnerKey(Minecraft mc, CoopWorld world) {
        if (!CoopWorlds.store().isOwner(world.worldId())) {
            return Outcome.fail("owner.not_owner", world.name());
        }
        Path target = mc.gameDirectory.toPath().resolve("dropin-owner-key-" + world.worldId().substring(0, 8) + ".key");
        try {
            CoopWorlds.store().exportOwnerKey(world.worldId(), target);
            return Outcome.ok(world, "owner.exported", target.toAbsolutePath().toString());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not export the owner key of {}", world.worldId(), e);
            return Outcome.fail("owner.export_failed", world.name());
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
