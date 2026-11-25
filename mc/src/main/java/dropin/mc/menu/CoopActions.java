package dropin.mc.menu;

import dropin.core.invite.Invite;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.core.world.ImportCheck;
import dropin.core.world.WorldArchive;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.HostSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.storage.SaveFormatOld;
import net.minecraft.world.storage.WorldInfo;

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
import java.util.stream.Stream;

/**
 * What the co-op menu does, without any widgets (DESIGN 4.4, 4.13), so the screens and the self-tests
 * run the same code.
 */
public final class CoopActions {
    private static final String K = "text.dropin_coop.";
    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * 1.12.2 port: the data version this game writes into level.dat ({@code WorldInfo.updateTagCompound} writes
     * {@code DataVersion} and {@code Version.Id} 1343; {@code DataFixesManager.createFixer} is {@code new DataFixer(1343)}).
     * {@code mc.getDataFixer().version} cannot be used: Forge wraps the fixer in a {@code CompoundDataFixer(0)}.
     */
    static final int GAME_DATA_VERSION = 1343;

    /** Set while the vanilla Create World screen was opened from "New": the next new world becomes co-op. */
    public static volatile boolean pendingNew;

    /** @param key the translation key of {@code message}, for the self-tests */
    public record Outcome(boolean ok, String key, ITextComponent message, CoopWorld world) {
        static Outcome ok(CoopWorld world, String key, Object... args) {
            return new Outcome(true, K + key, new TextComponentTranslation(K + key, args), world);
        }

        static Outcome fail(String key, Object... args) {
            return new Outcome(false, K + key, new TextComponentTranslation(K + key, args), null);
        }
    }

    private CoopActions() {
    }

    // ---- lookups -----------------------------------------------------------------------------

    /**
     * 1.12.2 port: 26.x {@code LevelStorageSource.getBaseDir()}. The game's save format is an
     * {@code AnvilSaveConverter} (a {@code SaveFormatOld}) on {@code <gameDir>/saves}.
     */
    public static Path savesDir(Minecraft mc) {
        if (mc.getSaveLoader() instanceof SaveFormatOld format) {
            return format.savesDirectory.toPath();
        }
        return mc.gameDir.toPath().resolve("saves");
    }

    /** 1.12.2 port: 26.x {@code LevelStorageSource.getLevelPath(levelId)}. */
    public static Path levelPath(Minecraft mc, String levelId) {
        return savesDir(mc).resolve(levelId);
    }

    /** world id -> folder name under saves/, for every folder marked as a co-op world. */
    public static Map<String, String> folders(Minecraft mc) {
        Map<String, String> result = new TreeMap<>();
        Path saves = savesDir(mc);
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
            return CoopWorlds.forFolder(levelPath(mc, levelId)).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- import ------------------------------------------------------------------------------

    /** Import from saves (DESIGN 4.4): the three checks in order, the optional backup, then the key files. */
    public static Outcome importFromSaves(Minecraft mc, String levelId, boolean backup) {
        Path folder = levelPath(mc, levelId);
        if (!Files.isRegularFile(folder.resolve("level.dat"))) {
            return Outcome.fail("import.no_level_dat", levelId);
        }
        // Check 1 first: the summary of a world this game has open cannot always be read.
        if (isOpenHere(mc, folder)) {
            return Outcome.fail("import.open", levelId);
        }
        // 1.12.2 port: 26.x LevelSummary -> the folder's WorldInfo (level.dat, then level.dat_old), null if unreadable.
        WorldInfo summary;
        try {
            summary = mc.getSaveLoader().getWorldInfo(levelId);
        } catch (RuntimeException e) {
            DropIn.LOGGER.warn("Could not read world {}", levelId, e);
            summary = null;
        }
        if (summary == null) {
            return Outcome.fail("import.unreadable", levelId);
        }
        String levelName = summary.getWorldName();

        Optional<String> marker = CoopWorldStore.readMarker(folder);
        boolean keyKnown = false;
        if (marker.isPresent()) {
            try {
                keyKnown = CoopWorlds.store().get(marker.get()).isPresent();
            } catch (IOException | RuntimeException e) {
                keyKnown = false;
            }
        }
        // 1.12.2 port: 26.x LevelSummary.isLocked() has no counterpart. session.lock is not an OS lock on this
        // version (SaveHandler writes a time stamp into it), so another game cannot be detected; only this game's
        // own open world counts as open.
        // TODO(1.12.2): a world open in a second game on this PC is not detected as open.
        boolean open = isOpenHere(mc, folder);
        // 1.12.2 port: the world's data version is level.dat's Version.Id (0 for worlds older than 1.9).
        ImportCheck.Result check = ImportCheck.check(open, marker.isPresent(), keyKnown,
                summary.getVersionId(), GAME_DATA_VERSION);
        switch (check) {
            case OPEN:
                return Outcome.fail("import.open", levelName);
            case ALREADY_COOP:
                return Outcome.fail("import.already_coop", levelName);
            case NEEDS_INVITE:
                return Outcome.fail("import.needs_invite", levelName);
            case TOO_OLD:
                return Outcome.fail("import.too_old", levelName);
            case TOO_NEW:
                return Outcome.fail("import.too_new", levelName);
            default:
                break;
        }

        if (backup) {
            try {
                makeWorldBackup(mc, levelId, folder);
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.warn("Backup of {} failed", levelId, e);
                return Outcome.fail("import.backup_failed", levelName);
            }
        }
        try {
            CoopWorld world = CoopWorlds.store().importWorld(folder, levelName,
                    mc.getSession().getProfile().getId().toString(), mc.getSession().getUsername(), now(), RANDOM);
            DropIn.LOGGER.info("Imported '{}' as co-op world {}", world.name(), world.worldId());
            return Outcome.ok(world, "import.done", world.name());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Import of {} failed", levelId, e);
            return Outcome.fail("import.failed", levelName);
        }
    }

    /**
     * 1.12.2 port: 1.12.2 has no {@code LevelStorageAccess.makeWorldBackup()}. Same result as 26.x: the closed world
     * as {@code <gameDir>/backups/<yyyy-MM-dd_HH-mm-ss>_<levelId>.zip} (without session.lock).
     */
    private static void makeWorldBackup(Minecraft mc, String levelId, Path folder) throws IOException {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path zip = mc.gameDir.toPath().resolve("backups").resolve(stamp + "_" + levelId + ".zip");
        int files = WorldArchive.exportZip(folder, zip);
        DropIn.LOGGER.info("Backup of {}: {} ({} files)", levelId, zip, files);
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
        Path saves = savesDir(mc);
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

    /** 1.12.2 port: 26.x {@code server.getWorldPath(LevelResource.ROOT)} is {@code saves/<server.getFolderName()>}. */
    private static Path worldFolder(Minecraft mc, MinecraftServer server) {
        return levelPath(mc, server.getFolderName());
    }

    private static boolean isOpenHere(Minecraft mc, Path folder) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return false;
        }
        try {
            Path open = worldFolder(mc, server);
            return Files.exists(open) && Files.isSameFile(open, folder);
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
        registerOpenWorld(Minecraft.getMinecraft(), server);
    }

    /** "Make co-op" in the pause menu: import in place of the world that is open. */
    public static boolean canMakeCoop(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        return server != null && mc.isIntegratedServerRunning() && !server.getPublic() && !CoopWorlds.isCoopServer(server);
    }

    public static Outcome makeCoop(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null || !canMakeCoop(mc)) {
            return Outcome.fail("import.failed", "");
        }
        return registerOpenWorld(mc, server);
    }

    private static Outcome registerOpenWorld(Minecraft mc, MinecraftServer server) {
        Path folder = worldFolder(mc, server).toAbsolutePath().normalize();
        // 1.12.2 port: 26.x WorldData.getLevelName(). Before the worlds are loaded (a new world) the server only
        // knows the name it was started with.
        String name = server.worlds.length > 0 && server.worlds[0] != null
                ? server.worlds[0].getWorldInfo().getWorldName() : server.getWorldName();
        String uuid = mc.getSession().getProfile().getId().toString();
        String player = mc.getSession().getUsername();
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
            HexFormat hex = HexFormat.of();
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
        if (mc.getIntegratedServer() != null) {
            throw new IOException("a world is open");
        }
        String name = world.name().replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path zip = dropin.mc.savepoint.SavePointActions.backupsDir(folder)
                .resolve((name.isEmpty() ? "world" : name) + "-world-" + stamp + ".zip");
        int files = WorldArchive.exportZip(folder, zip);
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
            String name = I18n.format(K + "invite.default_name", worldId.substring(0, 6));
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
            CoopWorlds.store().remove(world.worldId(), folder == null ? null : levelPath(mc, folder));
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
        Path target = mc.gameDir.toPath().resolve("dropin-owner-key-" + world.worldId().substring(0, 8) + ".key");
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
