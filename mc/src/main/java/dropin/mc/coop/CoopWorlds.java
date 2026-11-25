package dropin.mc.coop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.pkarr.FileRendezvous;
import dropin.core.pkarr.HttpPkarrClient;
import dropin.core.pkarr.PkarrRendezvous;
import dropin.core.pkarr.Rendezvous;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.tunnel.Agnos;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Co-op world lookups shared by client and server code. No client-only classes in here. */
public final class CoopWorlds {
    private static final Path CONFIG_DIR = Agnos.configDir().resolve("dropin");
    private static final CoopWorldStore STORE = new CoopWorldStore(CONFIG_DIR);
    private static final Map<MinecraftServer, Boolean> COOP_SERVERS = Collections.synchronizedMap(new WeakHashMap<>());

    private CoopWorlds() {
    }

    public static CoopWorldStore store() {
        return STORE;
    }

    /** The co-op world a save folder belongs to, if it is marked and this PC has its key. Never throws. */
    public static Optional<CoopWorld> forFolder(Path worldFolder) {
        try {
            return STORE.forFolder(worldFolder);
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("Could not read the co-op entry of {}", worldFolder.getFileName(), e);
            return Optional.empty();
        }
    }

    /** True if the world this server runs is a co-op world on this PC. Cached per server. */
    public static boolean isCoopServer(MinecraftServer server) {
        return COOP_SERVERS.computeIfAbsent(server, s -> forFolder(worldFolder(s)).isPresent());
    }

    /**
     * The folder of the world a server runs, absolute (26.x {@code getWorldPath(LevelResource.ROOT)}). 1.12.2 port:
     * {@code <saves>/<folder name>}, from the server's save format; valid before the worlds are loaded.
     */
    public static Path worldFolder(MinecraftServer server) {
        return server.getActiveAnvilConverter().getFile(server.getFolderName(), "level.dat").toPath()
                .toAbsolutePath().normalize().getParent();
    }

    /**
     * Where "who is hosting" is published for this world: pkarr relays, or, in a development environment
     * with {@code dropin.dev.sharedDir} set and {@code dropin.dev.online} not set, a file in that folder.
     */
    public static Rendezvous rendezvous(CoopWorld world) {
        Optional<String> shared = DropIn.switches().read("dropin.dev.sharedDir");
        boolean online = DropIn.switches().read("dropin.dev.online").map(Boolean::parseBoolean).orElse(false);
        if (shared.isPresent() && !online) {
            return new FileRendezvous(Path.of(shared.get()).resolve("rendezvous").resolve(world.worldId() + ".json"));
        }
        return new PkarrRendezvous(world.keys(), new HttpPkarrClient(relays(), Duration.ofSeconds(8)));
    }

    /** Makes a running server count as co-op from now on ("Make co-op" in the pause menu). */
    public static void markCoop(MinecraftServer server) {
        COOP_SERVERS.put(server, Boolean.TRUE);
    }

    /** {@code config/dropin/coop.json}: {@code "titleButton": false} hides the title-screen button (DESIGN 4.15). */
    public static boolean titleButton() {
        Path file = CONFIG_DIR.resolve("coop.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                return !json.has("titleButton") || json.get("titleButton").getAsBoolean();
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }
        return true;
    }

    /** A boolean of {@code config/dropin/coop.json}, e.g. {@code bulkSyncOverRelay}; the default when absent or unreadable. */
    public static boolean configFlag(String name, boolean fallback) {
        JsonElement value = configValue(name);
        try {
            return value == null ? fallback : value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** A number of {@code config/dropin/coop.json}, e.g. {@code checkpointSeconds}; the default when absent or unreadable. */
    public static int configInt(String name, int fallback) {
        JsonElement value = configValue(name);
        try {
            return value == null ? fallback : value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static JsonElement configValue(String name) {
        Path file = CONFIG_DIR.resolve("coop.json");
        if (Files.isRegularFile(file)) {
            try {
                return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().get(name);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** {@code config/dropin/coop.json}: {@code {"pkarrRelays": ["https://..."]}}. Defaults when absent or unreadable. */
    public static List<URI> relays() {
        Path file = CONFIG_DIR.resolve("coop.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!json.has("pkarrRelays")) {
                    return HttpPkarrClient.DEFAULT_RELAYS;
                }
                List<URI> relays = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray("pkarrRelays")) {
                    URI uri = URI.create(element.getAsString());
                    if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                        relays.add(uri);
                    }
                }
                if (!relays.isEmpty()) {
                    return relays;
                }
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.warn("config/dropin/coop.json is unreadable, using the default pkarr relays", e);
            }
        }
        return HttpPkarrClient.DEFAULT_RELAYS;
    }
}
