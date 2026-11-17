package dropin.mc.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.mods.ModDiff;
import dropin.core.mods.ModManifest;
import dropin.core.mods.ModScan;
import dropin.core.mods.ModStore;
import dropin.core.mods.ModrinthClient;
import dropin.core.policy.Policy;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.core.world.Member;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.tunnel.Agnos;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * The owner policy and mod list of a co-op world on this PC (DESIGN 4.13, 4.14): where they are stored,
 * how a received one is checked, and what this PC's {@code mods/} folder and the world's datapacks look
 * like next to them. No client-only classes in here.
 */
public final class WorldPolicy {
    /** DropIn's own mod id: its jar is never part of a mod list. */
    public static final String MOD_ID = "dropin";
    private static final String K = "text.dropin_coop.";
    private static final String KEEP_FILE = "keepmods.json";
    private static final Path CONFIG = Agnos.configDir().resolve("dropin");

    private WorldPolicy() {
    }

    /** A policy that was verified against the world's invite, with the mod list it names. */
    public record Held(Policy policy, ModManifest manifest) {
        public String[] json() {
            return new String[]{policy.toJson(), manifest.toJson()};
        }

        public long version() {
            return policy.version();
        }
    }

    /** What a {@code POLICY} message from a peer led to. */
    public record Exchange(Held effective, String[] reply, boolean adopted) {
    }

    // ---- this PC ------------------------------------------------------------------------------

    public static Path configDir() {
        return CONFIG;
    }

    /** The game's {@code mods/} folder: next to {@code config/}. */
    public static Path modsDir() {
        return Agnos.configDir().toAbsolutePath().normalize().getParent().resolve("mods");
    }

    public static ModStore store() {
        return new ModStore(CONFIG.resolve("modstore"));
    }

    public static String loader() {
        return Agnos.loaderName();
    }

    public static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** {@code config/dropin/coop.json}: modMaxFileMB, modMaxTotalMB, modMaxFiles. */
    public static ModManifest.Limits limits() {
        ModManifest.Limits fallback = ModManifest.Limits.DEFAULT;
        return new ModManifest.Limits((long) CoopWorlds.configInt("modMaxFileMB", (int) (fallback.maxFileBytes() >> 20)) << 20,
                (long) CoopWorlds.configInt("modMaxTotalMB", (int) (fallback.maxTotalBytes() >> 20)) << 20,
                CoopWorlds.configInt("modMaxFiles", fallback.maxFiles()));
    }

    /** The jars directly in {@code mods/}, without DropIn itself. */
    public static List<ModManifest.Entry> localMods() throws IOException {
        return ModScan.scan(modsDir(), Set.of(MOD_ID), loader());
    }

    /** File names the player marked "keep" ({@code config/dropin/keepmods.json}). */
    public static Set<String> keep() {
        Set<String> names = new TreeSet<>();
        Path file = CONFIG.resolve(KEEP_FILE);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement name : json.getAsJsonArray("keep")) {
                    names.add(name.getAsString());
                }
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.warn("config/dropin/{} is unreadable; no mod is kept", KEEP_FILE, e);
            }
        }
        return names;
    }

    public static void setKeep(String fileName, boolean keep) throws IOException {
        Set<String> names = keep();
        names.removeIf(name -> name.equalsIgnoreCase(fileName));
        if (keep) {
            names.add(ModManifest.safeName(fileName));
        }
        JsonArray array = new JsonArray();
        names.forEach(array::add);
        JsonObject json = new JsonObject();
        json.add("keep", array);
        Policy.writeAtomically(CONFIG.resolve(KEEP_FILE), json.toString());
    }

    /** What the swap helper must leave alone: the kept jars and DropIn's own jar. */
    public static List<String> planKeep() throws IOException {
        List<String> names = new ArrayList<>(keep());
        Path mods = modsDir();
        if (Files.isDirectory(mods)) {
            try (Stream<Path> files = Files.list(mods)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".jar") && MOD_ID.equals(ModScan.jarInfo(file, loader()).modId())) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    // ---- stored policy ------------------------------------------------------------------------

    /** The newest valid policy stored in this copy of the world, or null. An invalid file counts as none. */
    public static Held load(CoopWorld world, Path worldFolder) {
        if (worldFolder == null) {
            return null;
        }
        Path dir = worldFolder.resolve(CoopWorldStore.MARKER_DIR);
        Policy policy = Policy.load(dir.resolve(Policy.FILE));
        ModManifest manifest = ModManifest.load(dir.resolve(ModManifest.FILE));
        if (policy == null || manifest == null) {
            return null;
        }
        try {
            return verified(world, policy, manifest);
        } catch (IOException e) {
            DropIn.LOGGER.warn("The stored policy of '{}' is not valid and is ignored: {}", world.name(), e.getMessage());
            return null;
        }
    }

    /** @throws IOException with the reason if the policy or mod list must not be trusted */
    public static Held verify(CoopWorld world, String policyJson, String manifestJson) throws IOException {
        try {
            return verified(world, Policy.fromJson(policyJson), ModManifest.fromJson(manifestJson));
        } catch (IllegalArgumentException e) {
            throw new IOException("it cannot be read");
        }
    }

    private static Held verified(CoopWorld world, Policy policy, ModManifest manifest) throws IOException {
        String problem = policy.rejection(HexFormat.of().parseHex(world.ownerFingerprint()));
        if (problem != null) {
            throw new IOException(problem);
        }
        if (!manifest.hash().equals(policy.modManifestHash())) {
            throw new IOException("the mod list does not belong to the policy");
        }
        manifest.validate(limits());
        return new Held(policy, manifest);
    }

    /** Stores {@code candidate} if it is newer than what this copy holds. Returns whether it was stored. */
    public static synchronized boolean adopt(CoopWorld world, Path worldFolder, Held candidate) throws IOException {
        Held current = load(world, worldFolder);
        if (!Policy.replaces(candidate.policy(), current == null ? null : current.policy())) {
            return false;
        }
        Path dir = worldFolder.resolve(CoopWorldStore.MARKER_DIR);
        Policy.writeAtomically(dir.resolve(ModManifest.FILE), candidate.manifest().toJson());
        candidate.policy().save(dir.resolve(Policy.FILE));
        Policy policy = candidate.policy();
        try {
            CoopWorlds.store().learnOwnerKey(world.worldId(), policy.ownerKey());
            CoopWorlds.store().mergeMembers(world.worldId(),
                    List.of(new Member(policy.ownerUuid(), policy.ownerName(), policy.ts(), policy.ts(), Member.OWNER)), policy.ownerUuid());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("Could not note the owner of '{}'", world.name(), e);
        }
        DropIn.LOGGER.info("Policy version {} of '{}' stored: {} mods, {} banned, members may use commands: {}", policy.version(),
                world.name(), candidate.manifest().files().size(), policy.banned().size(), policy.membersMayUseCommands());
        return true;
    }

    /**
     * Handles a peer's {@code POLICY} message (empty strings: the peer has none). A valid newer policy is
     * stored; if this copy holds a newer one, it is the reply.
     *
     * @param makeFolder gives the world's folder when there is none yet and something must be stored
     * @throws IOException with the reason if what the peer sent must not be trusted
     */
    public static Exchange receive(CoopWorld world, Path worldFolder, Callable<Path> makeFolder, String policyJson,
                                   String manifestJson) throws IOException {
        Held mine = load(world, worldFolder);
        if (policyJson.isEmpty()) {
            return new Exchange(mine, mine == null ? null : mine.json(), false);
        }
        Held theirs = verify(world, policyJson, manifestJson);
        if (Policy.replaces(theirs.policy(), mine == null ? null : mine.policy())) {
            Path folder = worldFolder;
            if (folder == null) {
                try {
                    folder = makeFolder.call();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            adopt(world, folder, theirs);
            return new Exchange(theirs, null, true);
        }
        return new Exchange(mine, mine.version() > theirs.version() ? mine.json() : null, false);
    }

    // ---- comparing ----------------------------------------------------------------------------

    /** This PC's {@code mods/} folder against the world's mod list. */
    public static ModDiff diff(Held held) throws IOException {
        return ModDiff.of(held.manifest(), localMods(), keep());
    }

    /** Why this game cannot play the world at all (wrong Minecraft version or loader), or null. */
    public static Component environmentProblem(Held held) {
        Policy policy = held.policy();
        if (!policy.minecraftVersion().equals(minecraftVersion())) {
            return Component.translatable(K + "policy.version", policy.minecraftVersion(), minecraftVersion());
        }
        // DESIGN 4.14: a world without mods stays cross-loader.
        if (!held.manifest().isEmpty() && !policy.loader().equals(loader())) {
            return Component.translatable(K + "policy.loader", policy.loader(), loader());
        }
        return null;
    }

    /** True if the two lists name the same files with the same content (where a file can be downloaded does not count). */
    public static boolean sameFiles(ModManifest a, ModManifest b) {
        return fileKeys(a).equals(fileKeys(b));
    }

    private static Set<String> fileKeys(ModManifest manifest) {
        Set<String> keys = new TreeSet<>();
        for (ModManifest.Entry file : manifest.files()) {
            keys.add(file.path() + "|" + file.sha512());
        }
        return keys;
    }

    // ---- datapacks (the world must be closed) ---------------------------------------------------

    /** Path under {@code datapacks/} to SHA-256 of every file there. */
    public static Map<String, String> datapackFiles(Path worldFolder) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Path dir = worldFolder.resolve("datapacks");
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                try {
                    files.put(dir.relativize(file).toString().replace('\\', '/'),
                            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }
            }
        }
        return files;
    }

    /**
     * The world datapacks that are switched on: the {@code file/...} entries of {@code DataPacks.Enabled} in
     * level.dat. Packs that come with the game or a loader are left out, so they cannot make two loaders differ.
     */
    public static List<String> datapacksEnabled(Path worldFolder) throws IOException {
        List<String> enabled = new ArrayList<>();
        Path levelDat = worldFolder.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return enabled;
        }
        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        ListTag list = root.getCompoundOrEmpty("Data").getCompoundOrEmpty("DataPacks").getListOrEmpty("Enabled");
        for (int i = 0; i < list.size(); i++) {
            String name = list.getStringOr(i, "");
            if (name.startsWith("file/")) {
                enabled.add(name);
            }
        }
        return enabled;
    }

    // ---- the owner ----------------------------------------------------------------------------

    /** Development and self-tests run offline: Modrinth is only asked in a normal game or an online test. */
    public static boolean modrinthEnabled() {
        return DropIn.switches().selfTest().isEmpty() || DropIn.switches().flag("dropin.dev.online");
    }

    public static ModrinthClient modrinth() {
        String version = DropIn.class.getPackage().getImplementationVersion();
        return new ModrinthClient(ModrinthClient.PRODUCTION, "dropin-coop/" + (version == null ? "dev" : version),
                Duration.ofSeconds(15));
    }

    /** The mod list of this PC as the owner would sign it: the scan of {@code mods/}, with Modrinth sources where known. */
    public static ModManifest ownerManifest() throws IOException {
        ModManifest manifest = new ModManifest(localMods());
        if (!manifest.isEmpty() && modrinthEnabled()) {
            try {
                manifest = manifest.withSources(modrinth().lookup(manifest.files().stream().map(ModManifest.Entry::sha512).toList()));
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.warn("Modrinth's hash lookup failed; friends will get these mods from a host instead: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        manifest.validate(limits());
        return manifest;
    }

    /** Signs and stores a new policy version with the owner key of this PC. */
    public static Held sign(CoopWorld world, Path worldFolder, long version, String ownerUuid, String ownerName, ModManifest manifest,
                            Map<String, String> datapackFiles, List<String> datapacksEnabled, List<String> banned,
                            boolean membersMayUseCommands) throws IOException {
        KeyPair key = CoopWorlds.store().ownerKey(world.worldId())
                .orElseThrow(() -> new IOException("the owner key of this world is not on this PC"));
        Policy policy = Policy.sign(new Policy(version, "", ownerUuid, ownerName, loader(), minecraftVersion(), manifest.hash(),
                datapackFiles, datapacksEnabled, banned, membersMayUseCommands, System.currentTimeMillis() / 1000, ""), key);
        Held held = verified(world, policy, manifest);
        if (!adopt(world, worldFolder, held)) {
            throw new IOException("a newer policy is already stored");
        }
        return held;
    }
}
