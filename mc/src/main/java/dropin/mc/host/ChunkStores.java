package dropin.mc.host;

import dropin.core.manifest.RecordKey;
import dropin.core.world.CoopWorldStore;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The game's chunk storage objects of the worlds that are open, and the dirty set of DESIGN 4.5: every
 * chunk, entity and POI write of a co-op world passes {@code IOWorker.store}, where its key is recorded.
 * World data is only ever read back through these objects, never from the region files.
 */
public final class ChunkStores {
    /**
     * @param dim       dimension id, for example {@code minecraft:overworld}
     * @param kind      region, entities or poi
     * @param dir       the folder of the region files
     * @param worldRoot the world folder, derived from {@code dir}
     */
    public record Store(IOWorker worker, String dim, String kind, Path dir, Path worldRoot) {
        /**
         * False when another mod put its own class in place of the game's storage worker (C2ME does): such a
         * class overrides the methods this mod watches, so its writes are not seen and must be found by comparing.
         */
        public boolean writesSeen() {
            return worker.getClass() == IOWorker.class;
        }
    }

    private static final Map<IOWorker, Store> STORES = new ConcurrentHashMap<>();
    private static final Set<Path> TRACKED = ConcurrentHashMap.newKeySet();
    private static final Map<Path, Set<String>> DIRTY = new ConcurrentHashMap<>();

    private ChunkStores() {
    }

    /** Called when the game creates a chunk storage worker. */
    public static void register(IOWorker worker, Path dir) {
        Path folder = dir.toAbsolutePath().normalize();
        // The kind is the folder name: the storage type of region files is "chunk", not "region".
        String kind = folder.getFileName() == null ? "" : folder.getFileName().toString();
        if (!RecordKey.KINDS.contains(kind)) {
            return;
        }
        // 1.20.1 port: the storage worker is not told its dimension (RegionStorageInfo came in 1.20.5), so it is
        // read from the folder's place under the world folder.
        String dim = dimensionOf(folder);
        if (dim == null) {
            return;
        }
        Path root = worldRoot(folder, dim);
        if (root == null) {
            return;
        }
        // A worker of an earlier session of this world that was never reported closed (a replaced class's close()
        // is not seen either) must not be asked for chunks again.
        STORES.values().removeIf(old -> old.dir().equals(folder));
        STORES.put(worker, new Store(worker, dim, kind, folder, root));
        if (CoopWorldStore.readMarker(root).isPresent()) {
            TRACKED.add(root);
        }
    }

    public static void unregister(IOWorker worker) {
        STORES.remove(worker);
    }

    /** Called after the game handed a chunk to a storage worker. */
    public static void stored(IOWorker worker, ChunkPos pos) {
        Store store = STORES.get(worker);
        if (store != null && TRACKED.contains(store.worldRoot())) {
            DIRTY.computeIfAbsent(store.worldRoot(), root -> ConcurrentHashMap.newKeySet())
                    .add(RecordKey.chunk(store.dim(), store.kind(), pos.x, pos.z));
        }
    }

    /**
     * 1.20.1 port: the dimension whose chunks {@code <world>/<storage folder>/<kind>} holds. Of the ancestors whose
     * relative path is a dimension's storage folder ({@link RecordKey#dimensionOfFolder}), the one with a
     * {@code level.dat} wins; without one, the deepest named storage folder (DIM-1, DIM1, dimensions/...) wins over
     * the overworld.
     */
    static String dimensionOf(Path folder) {
        Path storage = folder.getParent();
        if (storage == null) {
            return null;
        }
        String named = null;
        for (Path root = storage; root != null && root.getNameCount() > 0; root = root.getParent()) {
            String dim = RecordKey.dimensionOfFolder(root.relativize(storage).toString().replace('\\', '/'));
            if (dim == null) {
                continue;
            }
            if (java.nio.file.Files.isRegularFile(root.resolve("level.dat"))) {
                return dim;
            }
            if (named == null && !dim.equals("minecraft:overworld")) {
                named = dim;
            }
        }
        return named != null ? named : "minecraft:overworld";
    }

    /**
     * {@code <world>/<storage folder of the dimension>/<kind>} (1.21.1 layout: {@link RecordKey#storageFolder}):
     * the world folder, or null if the folder is not where the game keeps that dimension's chunks.
     */
    private static Path worldRoot(Path dir, String dim) {
        if (!RecordKey.validDimension(dim)) {
            return null;
        }
        String storage = RecordKey.storageFolder(dim);
        int depth = storage.isEmpty() ? 0 : storage.split("/").length;
        Path root = dir.getParent();
        for (int i = 0; i < depth && root != null; i++) {
            root = root.getParent();
        }
        if (root == null || root.getNameCount() == 0) {
            return null;
        }
        String relative = root.relativize(dir.getParent()).toString().replace('\\', '/');
        return relative.equals(storage) ? root : null;
    }

    /** Starts recording writes of this world (a world made co-op while it is open). */
    public static void track(Path worldRoot) {
        TRACKED.add(worldRoot);
    }

    public static void untrack(Path worldRoot) {
        TRACKED.remove(worldRoot);
        DIRTY.remove(worldRoot);
    }

    /** Takes the keys written since the last call. */
    public static Set<String> drain(Path worldRoot) {
        Set<String> taken = new HashSet<>();
        Set<String> dirty = DIRTY.get(worldRoot);
        if (dirty != null) {
            for (String key : dirty) {
                if (dirty.remove(key)) {
                    taken.add(key);
                }
            }
        }
        return taken;
    }

    public static void markDirty(Path worldRoot, Collection<String> keys) {
        if (!keys.isEmpty()) {
            DIRTY.computeIfAbsent(worldRoot, root -> ConcurrentHashMap.newKeySet()).addAll(keys);
        }
    }

    /** The class of a storage worker of this world whose writes are not seen, or null if all are the game's own. */
    public static String unseenWriter(Path worldRoot) {
        for (Store store : STORES.values()) {
            if (store.worldRoot().equals(worldRoot) && !store.writesSeen()) {
                return store.worker().getClass().getName();
            }
        }
        return null;
    }

    public static List<Store> of(Path worldRoot) {
        List<Store> stores = new ArrayList<>();
        for (Store store : STORES.values()) {
            if (store.worldRoot().equals(worldRoot)) {
                stores.add(store);
            }
        }
        return stores;
    }

    public static Store find(Path worldRoot, String dim, String kind) {
        for (Store store : STORES.values()) {
            if (store.worldRoot().equals(worldRoot) && store.dim().equals(dim) && store.kind().equals(kind)) {
                return store;
            }
        }
        return null;
    }
}
