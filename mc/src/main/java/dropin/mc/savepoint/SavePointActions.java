package dropin.mc.savepoint;

import dropin.core.manifest.Manifest;
import dropin.core.savepoint.SavePoints;
import dropin.core.savepoint.SavePoints.Point;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.HostSession;
import dropin.mc.handoff.HostHandoff;
import dropin.mc.host.HostSync;
import dropin.mc.member.RecordApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Save points (DESIGN 4.16): made from a hosted world through the game's storage objects, restored only into
 * a world that is closed on this PC.
 */
public final class SavePointActions {
    private static final String K = "text.dropin_coop.savepoint.";
    private static final long CLOSE_TIMEOUT_MS = 180_000;

    private SavePointActions() {
    }

    /** {@code saves/<folder>/dropin/backups/}. */
    public static Path backupsDir(Path folder) {
        return folder.resolve(CoopWorldStore.MARKER_DIR).resolve("backups");
    }

    public static SavePoints store(Path folder) {
        return new SavePoints(backupsDir(folder).resolve("savepoints"));
    }

    /** True if the game has a world open on this PC (the save point files of a hosted world go through the command). */
    public static boolean isOpen(Minecraft mc) {
        return mc.getSingleplayerServer() != null;
    }

    /** From the hosted world: a checkpoint, then every record the store does not have yet. Not the server thread. */
    public static Point createHosted(HostSync sync, String name) throws IOException, InterruptedException {
        if (!SavePoints.validName(name)) {
            throw new IOException("not a save point name: " + name);
        }
        sync.checkpoint(true);
        return store(sync.folder()).create(name, false, sync.seq(), sync.manifestSnapshot(), sync::read);
    }

    /** From a world that is closed on this PC. */
    public static Point createClosed(Path folder, String name) throws IOException {
        Manifest now = RecordApplier.scan(folder);
        try (RecordApplier files = new RecordApplier(folder)) {
            return store(folder).create(name, false, seq(folder), now, files::read);
        }
    }

    private static long seq(Path folder) {
        return SyncState.load(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("state.json")).syncedThrough();
    }

    /**
     * Rolls a world that is closed on this PC back to a save point. The world as it is now is kept as the
     * automatic save point first (unless that is the one being restored).
     *
     * @return how many records were written or deleted
     */
    public static int restoreClosed(Path folder, Point point) throws IOException {
        SavePoints points = store(folder);
        Manifest now = RecordApplier.scan(folder);
        int changed = 0;
        try (RecordApplier files = new RecordApplier(folder)) {
            if (!point.auto()) {
                points.create(null, true, seq(folder), now, files::read);
            }
            Manifest target = points.manifest(point);
            for (String key : target.keys()) {
                String hash = target.get(key);
                if (!hash.equals(now.get(key))) {
                    files.apply(key, points.read(hash));
                    changed++;
                }
            }
            for (String key : now.keys()) {
                if (target.get(key) == null) {
                    files.delete(key);
                    changed++;
                }
            }
            files.flush();
        }
        // The next host start compares every record with the manifest (HostSync.takeRescan).
        Files.writeString(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("rescan"), "");
        DropIn.LOGGER.info("Restored save point '{}' into {}: {} records changed", point.name(), folder.getFileName(), changed);
        return changed;
    }

    /**
     * The owner's command: closes the hosted world (the members wait for this host), restores the save point
     * and hosts again. Any thread.
     */
    public static void restoreHosted(Minecraft mc, String name) throws IOException {
        HostSync sync = HostSync.current();
        if (sync == null) {
            throw new IOException("not hosting");
        }
        Path folder = sync.folder();
        CoopWorld world = sync.world();
        Point point = store(folder).find(name).orElseThrow(() -> new IOException("no save point named " + name));
        mc.execute(() -> {
            IntegratedServer server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            HostHandoff.quitToReturn(mc, sync);
            mc.gui.setScreen(new GenericMessageScreen(Component.translatable(K + "restoring")));
            Thread thread = new Thread(() -> {
                try {
                    long deadline = System.currentTimeMillis() + CLOSE_TIMEOUT_MS;
                    while (!server.isShutdown() || HostSession.current() != null) {
                        if (System.currentTimeMillis() > deadline) {
                            throw new IOException("the world did not close");
                        }
                        Thread.sleep(100);
                    }
                    restoreClosed(folder, point);
                    CoopLauncher.hostAfterHandoff(mc, world, folder);
                } catch (Exception e) {
                    DropIn.LOGGER.error("Restoring save point '{}' of '{}' failed", point.name(), world.name(), e);
                    mc.execute(() -> mc.gui.setScreen(new TitleScreen()));
                }
            }, "dropin-coop-restore");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static String fileName(String text) {
        String safe = text.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isEmpty() ? "world" : safe;
    }

    /** Writes the save point as one file into the backups folder. */
    public static Path export(CoopWorld world, Path folder, Point point) throws IOException {
        Path file = backupsDir(folder).resolve(fileName(world.name()) + "-" + point.name() + SavePoints.FILE_SUFFIX);
        Files.createDirectories(file.getParent());
        store(folder).exportTo(point, world.worldId(), file);
        return file;
    }

    /** Imports the given files; with none given, every exported file in the backups folder whose name is free. */
    public static List<Point> importFiles(CoopWorld world, Path folder, List<Path> files) throws IOException {
        SavePoints points = store(folder);
        List<Path> candidates = new ArrayList<>(files);
        boolean explicit = !candidates.isEmpty();
        if (!explicit && Files.isDirectory(backupsDir(folder))) {
            try (Stream<Path> found = Files.list(backupsDir(folder))) {
                found.filter(file -> file.getFileName().toString().endsWith(SavePoints.FILE_SUFFIX)).sorted().forEach(candidates::add);
            }
        }
        List<Point> imported = new ArrayList<>();
        IOException failure = null;
        for (Path file : candidates) {
            try {
                imported.add(points.importFrom(world.worldId(), file));
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null && (explicit || imported.isEmpty() && candidates.size() == 1)) {
            throw failure;
        }
        return imported;
    }

    /** Writes the save point as an ordinary world under saves/, with no link to the co-op world. Returns its folder name. */
    public static String makeSinglePlayer(Minecraft mc, CoopWorld world, Path folder, Point point) throws IOException {
        SavePoints points = store(folder);
        String base = fileName(world.name()) + "-" + point.name();
        String name = base;
        for (int n = 2; Files.exists(mc.getLevelSource().getLevelPath(name)); n++) {
            name = base + "-" + n;
        }
        Path target = mc.getLevelSource().getLevelPath(name);
        Files.createDirectories(target);
        Manifest manifest = points.manifest(point);
        try (RecordApplier files = new RecordApplier(target)) {
            for (String key : manifest.keys()) {
                files.apply(key, points.read(manifest.get(key)));
            }
            files.flush();
        }
        return name;
    }
}
