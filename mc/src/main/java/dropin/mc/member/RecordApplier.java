package dropin.mc.member;

import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.world.CoopWorldStore;
import dropin.mc.host.ChunkBytes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes records into a member's copy of a world (DESIGN 4.7). The copy is <b>not open</b> in the game
 * (its owner plays as a remote client), so chunk records go through the game's own region-file classes,
 * opened standalone, and file records through a temp file and an atomic move. Never use this on a world
 * that is open on this machine.
 */
public final class RecordApplier implements AutoCloseable {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final Path root;
    private final String levelId;
    private final Map<String, RegionFileStorage> storages = new HashMap<>();
    /** Once closed, nothing is read or written any more: a late frame must never reopen the region files. */
    private boolean closed;

    public RecordApplier(Path worldFolder) {
        this.root = worldFolder.toAbsolutePath().normalize();
        this.levelId = root.getFileName().toString();
    }

    public synchronized void apply(String key, byte[] bytes) throws IOException {
        ensureOpen();
        if (RecordKey.isChunk(key)) {
            RecordKey.Chunk chunk = RecordKey.parseChunk(key);
            CompoundTag tag = ChunkBytes.parse(bytes);
            ((dropin.mixin.mc.RegionFileStorageAccess) (Object) storage(chunk.dim(), chunk.kind())).dropin$write(new ChunkPos(chunk.cx(), chunk.cz()), tag);
        } else {
            Path file = filePath(key);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".dropin.tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public synchronized void delete(String key) throws IOException {
        ensureOpen();
        if (RecordKey.isChunk(key)) {
            RecordKey.Chunk chunk = RecordKey.parseChunk(key);
            ((dropin.mixin.mc.RegionFileStorageAccess) (Object) storage(chunk.dim(), chunk.kind())).dropin$write(new ChunkPos(chunk.cx(), chunk.cz()), null);
        } else {
            Files.deleteIfExists(filePath(key));
        }
    }

    /** The canonical bytes of a chunk record in this copy, or null if the copy has no such chunk. */
    public synchronized byte[] readChunk(String key) throws IOException {
        RecordKey.Chunk chunk = RecordKey.parseChunk(key);
        CompoundTag tag = storage(chunk.dim(), chunk.kind()).read(new ChunkPos(chunk.cx(), chunk.cz()));
        return tag == null ? null : ChunkBytes.canonical(tag);
    }

    /** The bytes of any record of this copy, or null if the copy has no such record. */
    public synchronized byte[] read(String key) throws IOException {
        ensureOpen();
        if (RecordKey.isChunk(key)) {
            return readChunk(key);
        }
        Path file = filePath(key);
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    public synchronized void flush() throws IOException {
        ensureOpen();
        for (RegionFileStorage storage : storages.values()) {
            storage.flush();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (RegionFileStorage storage : storages.values()) {
            try {
                storage.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        storages.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private Path filePath(String key) throws IOException {
        String relative = RecordKey.filePath(key);
        Path file = root.resolve(relative).normalize();
        if (!RecordKey.fileAllowed(relative) || !file.startsWith(root)) {
            throw new IOException("Refused file record " + key);
        }
        return file;
    }

    /** Whether {@link #close()} was called. */
    public synchronized boolean isClosed() {
        return closed;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("The copy at " + root + " was let go of");
        }
    }

    private RegionFileStorage storage(String dim, String kind) throws IOException {
        ensureOpen();
        String id = dim + "|" + kind;
        RegionFileStorage storage = storages.get(id);
        if (storage == null) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
            Path folder = DimensionType.getStorageFolder(dimension, root).resolve(kind).normalize();
            if (!folder.startsWith(root)) {
                throw new IOException("Refused dimension " + dim);
            }
            Files.createDirectories(folder);
            storage = dropin.mixin.mc.RegionFileStorageAccess.dropin$create(new RegionStorageInfo(levelId, dimension, kind), folder, false);
            storages.put(id, storage);
        }
        return storage;
    }

    /**
     * Hashes a <b>closed</b> world folder into a manifest, reading the chunks with the game's region-file
     * classes: what a host would report for the same data. Used to pick up a copy that was brought over by
     * hand, and by the self-tests to prove that a downloaded copy matches the host's root.
     */
    public static Manifest scan(Path worldFolder) throws IOException {
        Path root = worldFolder.toAbsolutePath().normalize();
        Manifest manifest = new Manifest();
        try (RecordApplier reader = new RecordApplier(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String relative = root.relativize(dir).toString().replace('\\', '/');
                    if (relative.equals(CoopWorldStore.MARKER_DIR)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String kind = dir.getFileName().toString();
                    // 1.21.1 layout: <world>/region, DIM-1/region, DIM1/region, dimensions/<ns>/<path>/region
                    String dim = dir.equals(root) || !RecordKey.KINDS.contains(kind) ? null
                            : RecordKey.dimensionOfFolder(root.relativize(dir.getParent()).toString().replace('\\', '/'));
                    if (dim != null) {
                        reader.scanRegions(dir, dim, kind, manifest);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (RecordKey.fileAllowed(relative, attrs.size())) {
                        manifest.put(RecordKey.file(relative), Manifest.hash(Files.readAllBytes(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return manifest;
    }

    private void scanRegions(Path dir, String dim, String kind, Manifest manifest) throws IOException {
        RegionFileStorage storage = storage(dim, kind);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int rx = Integer.parseInt(matcher.group(1));
                int rz = Integer.parseInt(matcher.group(2));
                for (int index = 0; index < 1024; index++) {
                    ChunkPos pos = new ChunkPos(rx * 32 + (index & 31), rz * 32 + (index >> 5));
                    CompoundTag tag = storage.read(pos);
                    if (tag != null) {
                        manifest.put(RecordKey.chunk(dim, kind, pos.x, pos.z), Manifest.hash(ChunkBytes.canonical(tag)));
                    }
                }
            }
        }
    }
}
