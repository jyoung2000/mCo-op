package dropin.mc.host;

import dropin.core.manifest.RecordKey;
import dropin.core.world.CoopWorldStore;
import dropin.mc.compat.Dims;
import dropin.mixin.mc.AnvilChunkLoaderAccess;
import dropin.mixin.mc.RegionFileAccess;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's chunk storage objects of the worlds that are open, and the dirty set of DESIGN 4.5: every
 * chunk write of a co-op world passes {@code AnvilChunkLoader.addChunkToPending}, where its key is recorded.
 * World data is only ever read back through these objects (the loader's pending map and the game's
 * {@code RegionFileCache}), never by opening the region files of an open world in any other way.
 *
 * <p>1.12.2 port: one {@code AnvilChunkLoader} per dimension, only the "region" kind (entities live in the chunk
 * NBT; there is no POI storage). Its {@code chunkSaveLocation} is the dimension folder: the world folder for the
 * overworld, {@code DIM-1}, {@code DIM1} and {@code DIM<N>} (Forge's default {@code WorldProvider.getSaveFolder()})
 * for the others; the region files are in its {@code region/} subfolder.
 */
public final class ChunkStores {
    /** The only kind of chunk storage 1.12.2 has. */
    public static final String KIND = "region";
    private static final Pattern DIM_FOLDER = Pattern.compile("DIM(-?\\d+)");
    /** How long a read waits for the file IO thread to finish writing the chunk it took from the pending map. */
    private static final long BEING_SAVED_WAIT_MS = 10_000;

    /**
     * @param loader    the dimension's chunk loader
     * @param dim       dimension id, for example {@code minecraft:overworld} (dropin.mc.compat.Dims)
     * @param kind      always "region" on 1.12.2
     * @param dir       the folder of the region files ({@code <dimension folder>/region})
     * @param worldRoot the world folder, derived from the dimension folder
     */
    public record Store(AnvilChunkLoader loader, String dim, String kind, Path dir, Path worldRoot) {
        /**
         * False when another mod put its own class in place of the game's chunk loader: such a class may override
         * the methods this mod watches, so its writes are not seen and must be found by comparing.
         */
        public boolean writesSeen() {
            return loader.getClass() == AnvilChunkLoader.class;
        }

        /** The dimension folder the game hands to {@code RegionFileCache} ({@code chunkSaveLocation}). */
        public File location() {
            return loader.chunkSaveLocation;
        }
    }

    private static final Map<AnvilChunkLoader, Store> STORES = new ConcurrentHashMap<>();
    private static final Set<Path> TRACKED = ConcurrentHashMap.newKeySet();
    private static final Map<Path, Set<String>> DIRTY = new ConcurrentHashMap<>();

    private ChunkStores() {
    }

    /** Called when the game creates a chunk loader (AnvilChunkLoaderMixin). */
    public static void register(AnvilChunkLoader loader, File location) {
        if (location == null) {
            return;
        }
        Path folder = location.toPath().toAbsolutePath().normalize();
        Path root;
        String dim;
        if (isWorldRoot(folder)) {
            root = folder;
            dim = Dims.OVERWORLD;
        } else {
            Matcher matcher = folder.getFileName() == null ? null : DIM_FOLDER.matcher(folder.getFileName().toString());
            Path parent = folder.getParent();
            if (matcher == null || !matcher.matches() || parent == null || !isWorldRoot(parent)) {
                // TODO(1.12.2): a mod's dimension whose WorldProvider.getSaveFolder() is not DIM<N> is not tracked:
                //  its record keys would name a folder (DIM<N>) the game does not use. Such chunks are not synced.
                return;
            }
            int number;
            try {
                number = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return;
            }
            root = parent;
            dim = Dims.idOf(number);
        }
        Path dir = folder.resolve(KIND);
        // A loader of an earlier session of this world must not be asked for chunks again. 1.12.2 has no close()
        // on the chunk loader, so the old one is dropped when the game makes a new one for the same folder.
        STORES.values().removeIf(old -> old.dir().equals(dir));
        STORES.put(loader, new Store(loader, dim, KIND, dir, root));
        if (CoopWorldStore.readMarker(root).isPresent()) {
            TRACKED.add(root);
        }
    }

    /** The game makes {@code session.lock} in the world folder before it makes any chunk loader. */
    private static boolean isWorldRoot(Path folder) {
        return Files.isRegularFile(folder.resolve("session.lock")) || Files.isRegularFile(folder.resolve("level.dat"));
    }

    /** Called after the game handed a chunk to a chunk loader's pending map. */
    public static void stored(AnvilChunkLoader loader, ChunkPos pos) {
        Store store = STORES.get(loader);
        if (store != null && TRACKED.contains(store.worldRoot())) {
            DIRTY.computeIfAbsent(store.worldRoot(), root -> ConcurrentHashMap.newKeySet())
                    .add(RecordKey.chunk(store.dim(), store.kind(), pos.x, pos.z));
        }
    }

    /**
     * 1.12.2 layout: the folder, relative to the world folder, that the game keeps a dimension's chunks and saved
     * data in: "" for the overworld, {@code DIM-1}, {@code DIM1}, {@code DIM<N>} for {@code forge:dim<N>}; null if
     * the id is not a 1.12.2 dimension. (core's {@code RecordKey.storageFolder} describes the 1.21.1 layout.)
     */
    public static String dimensionFolder(String dim) {
        Integer number = Dims.numberOf(dim);
        if (number == null) {
            return null;
        }
        return number == 0 ? "" : "DIM" + number;
    }

    /** The dimension whose folder ({@link #dimensionFolder}) a path relative to the world folder is, or null. */
    public static String dimensionOfFolder(String relative) {
        if (relative.isEmpty()) {
            return Dims.OVERWORLD;
        }
        Matcher matcher = DIM_FOLDER.matcher(relative);
        if (!matcher.matches()) {
            return null;
        }
        try {
            String dim = Dims.idOf(Integer.parseInt(matcher.group(1)));
            return relative.equals(dimensionFolder(dim)) ? dim : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A chunk of an open world as the game's loader has it: the pending write if there is one, else what the game's
     * {@code RegionFileCache} reads from the region file ({@code AnvilChunkLoader.loadChunk__Async} does exactly
     * this, without the data fixer, which leaves chunks of this version unchanged). Null if the chunk was never saved.
     */
    public static NBTTagCompound read(Store store, int cx, int cz) throws IOException {
        AnvilChunkLoaderAccess access = (AnvilChunkLoaderAccess) store.loader();
        ChunkPos pos = new ChunkPos(cx, cz);
        long deadline = System.currentTimeMillis() + BEING_SAVED_WAIT_MS;
        while (true) {
            NBTTagCompound pending = access.dropin$chunksToSave().get(pos);
            if (pending != null) {
                return pending;
            }
            // The IO thread marks a chunk "being saved" before it takes it out of the pending map and clears the mark
            // once the region file has it: while marked, the file may still hold the older chunk.
            if (!access.dropin$chunksBeingSaved().contains(pos)) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("The game is still writing chunk " + cx + "," + cz);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted reading chunk " + cx + "," + cz);
            }
        }
        for (int attempt = 0; ; attempt++) {
            // getRegionFileIfExists, not getChunkInputStream: the latter creates an empty region file for a missing one.
            RegionFile region = RegionFileCache.getRegionFileIfExists(store.location(), cx, cz);
            if (region == null) {
                return null;
            }
            DataInputStream in = region.getChunkDataInputStream(cx & 31, cz & 31);
            if (in != null) {
                // The stream reads from a byte array the region file filled already.
                try (DataInputStream stream = in) {
                    return CompressedStreamTools.read(stream);
                }
            }
            // RegionFile answers null on an IO error too. The game's cache closes every region file when it holds 256;
            // a file closed under this read is not a missing chunk (that would send members a deletion): ask again.
            RandomAccessFile file = ((RegionFileAccess) (Object) region).dropin$dataFile();
            boolean closed = file == null || !file.getChannel().isOpen();
            if (!closed) {
                return null;
            }
            if (attempt >= 3) {
                throw new IOException("The region file of chunk " + cx + "," + cz + " was closed while it was read");
            }
        }
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

    /** The class of a chunk loader of this world whose writes are not seen, or null if all are the game's own. */
    public static String unseenWriter(Path worldRoot) {
        for (Store store : STORES.values()) {
            if (store.worldRoot().equals(worldRoot) && !store.writesSeen()) {
                return store.loader().getClass().getName();
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
