package dropin.mc.member;

import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.world.CoopWorldStore;
import dropin.mc.host.ChunkBytes;
import dropin.mc.host.ChunkStores;
import dropin.mixin.mc.RegionFileAccess;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes records into a member's copy of a world (DESIGN 4.7). The copy is <b>not open</b> in the game
 * (its owner plays as a remote client), so chunk records go through the game's own region-file classes,
 * opened standalone, and file records through a temp file and an atomic move. Never use this on a world
 * that is open on this machine.
 *
 * <p>1.12.2 port: chunks are written with this class's own {@code RegionFile} instances (never the game's
 * {@code RegionFileCache}, which belongs to the open world), {@code getChunkDataOutputStream} plus
 * {@code CompressedStreamTools.write}, as {@code AnvilChunkLoader.writeChunkData} does. Only the "region" kind
 * exists; the region files are in {@code <dimension folder>/region} (ChunkStores.dimensionFolder). Note that
 * {@code RegionFile} prints and swallows its own write errors, as it does for the game.
 */
public final class RecordApplier implements AutoCloseable {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    /** Region files kept open at once; the least recently used one is closed beyond that (the game keeps 256). */
    private static final int MAX_OPEN = 64;

    private final Path root;
    private final Map<Path, RegionFile> regions = new LinkedHashMap<>(16, 0.75f, true);
    /** Region files written since the last flush; they are forced to disk by {@link #flush()}. */
    private final Map<Path, RegionFile> written = new LinkedHashMap<>();
    /** Once closed, nothing is read or written any more: a late frame must never reopen the region files. */
    private boolean closed;

    public RecordApplier(Path worldFolder) {
        this.root = worldFolder.toAbsolutePath().normalize();
    }

    public synchronized void apply(String key, byte[] bytes) throws IOException {
        ensureOpen();
        if (RecordKey.isChunk(key)) {
            RecordKey.Chunk chunk = RecordKey.parseChunk(key);
            NBTTagCompound tag = ChunkBytes.parse(bytes);
            Path file = regionFile(chunk);
            RegionFile region = region(file, true);
            try {
                DataOutputStream out = region.getChunkDataOutputStream(chunk.cx() & 31, chunk.cz() & 31);
                // Closing the stream is what writes the chunk into the region file (RegionFile.ChunkBuffer.close).
                try (DataOutputStream stream = out) {
                    CompressedStreamTools.write(tag, stream);
                }
            } catch (RuntimeException e) {
                throw new IOException("Could not write " + key, e);
            }
            written.put(file, region);
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
            Path file = regionFile(chunk);
            RegionFile region = region(file, false);
            if (region == null) {
                return;
            }
            // 1.12.2 port: an offset of 0 is "never saved" (26.x: RegionFile.clear). The sectors are not reused
            // until the game rewrites the file; the chunk is gone either way.
            try {
                synchronized (region) {
                    ((RegionFileAccess) (Object) region).dropin$setOffset(chunk.cx() & 31, chunk.cz() & 31, 0);
                }
            } catch (RuntimeException e) {
                throw new IOException("Could not delete " + key, e);
            }
            written.put(file, region);
        } else {
            Files.deleteIfExists(filePath(key));
        }
    }

    /** The canonical bytes of a chunk record in this copy, or null if the copy has no such chunk. */
    public synchronized byte[] readChunk(String key) throws IOException {
        ensureOpen();
        RecordKey.Chunk chunk = RecordKey.parseChunk(key);
        RegionFile region = region(regionFile(chunk), false);
        NBTTagCompound tag = region == null ? null : read(region, chunk.cx(), chunk.cz());
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

    /** Forces every region file written since the last flush to disk. */
    public synchronized void flush() throws IOException {
        ensureOpen();
        for (RegionFile region : written.values()) {
            sync(region);
        }
        written.clear();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (RegionFile region : written.values()) {
            try {
                sync(region);
            } catch (IOException e) {
                failure = e;
            }
        }
        written.clear();
        for (RegionFile region : regions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        regions.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static void sync(RegionFile region) throws IOException {
        RandomAccessFile file = ((RegionFileAccess) (Object) region).dropin$dataFile();
        if (file != null && file.getChannel().isOpen()) {
            file.getFD().sync();
        }
    }

    private static NBTTagCompound read(RegionFile region, int cx, int cz) throws IOException {
        DataInputStream in;
        try {
            in = region.getChunkDataInputStream(cx & 31, cz & 31);
        } catch (RuntimeException e) {
            throw new IOException("Could not read chunk " + cx + "," + cz, e);
        }
        if (in == null) {
            return null;
        }
        try (DataInputStream stream = in) {
            return CompressedStreamTools.read(stream);
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

    /** {@code <world>/<dimension folder>/region/r.<rx>.<rz>.mca} of a chunk record. */
    private Path regionFile(RecordKey.Chunk chunk) throws IOException {
        String folder = ChunkStores.dimensionFolder(chunk.dim());
        if (folder == null || !chunk.kind().equals(ChunkStores.KIND)) {
            throw new IOException("Refused chunk record of " + chunk.dim() + "/" + chunk.kind());
        }
        Path dir = (folder.isEmpty() ? root : root.resolve(folder)).resolve(ChunkStores.KIND).normalize();
        if (!dir.startsWith(root)) {
            throw new IOException("Refused dimension " + chunk.dim());
        }
        return dir.resolve("r." + (chunk.cx() >> 5) + "." + (chunk.cz() >> 5) + ".mca");
    }

    /** This copy's own open region file, or null if {@code create} is false and the file does not exist. */
    private RegionFile region(Path file, boolean create) throws IOException {
        ensureOpen();
        RegionFile region = regions.get(file);
        if (region != null) {
            return region;
        }
        if (!create && !Files.isRegularFile(file)) {
            return null;
        }
        Files.createDirectories(file.getParent());
        region = new RegionFile(file.toFile());
        if (((RegionFileAccess) (Object) region).dropin$dataFile() == null) {
            throw new IOException("Could not open " + file);
        }
        regions.put(file, region);
        if (regions.size() > MAX_OPEN) {
            Iterator<Map.Entry<Path, RegionFile>> eldest = regions.entrySet().iterator();
            Map.Entry<Path, RegionFile> entry = eldest.next();
            if (entry.getValue() != region) {
                RegionFile old = entry.getValue();
                eldest.remove();
                if (written.remove(entry.getKey()) != null) {
                    sync(old);
                }
                old.close();
            }
        }
        return region;
    }

    /**
     * Hashes a <b>closed</b> world folder into a manifest, reading the chunks with the game's region-file
     * classes: what a host would report for the same data. Used to pick up a copy that was brought over by
     * hand, and by the self-tests to prove that a downloaded copy matches the host's root.
     */
    public static Manifest scan(Path worldFolder) throws IOException {
        Path root = worldFolder.toAbsolutePath().normalize();
        Manifest manifest = new Manifest();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String relative = root.relativize(dir).toString().replace('\\', '/');
                if (relative.equals(CoopWorldStore.MARKER_DIR)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // 1.12.2 layout: <world>/region, DIM-1/region, DIM1/region, DIM<N>/region
                String dim = dir.equals(root) || !dir.getFileName().toString().equals(ChunkStores.KIND) ? null
                        : ChunkStores.dimensionOfFolder(root.relativize(dir.getParent()).toString().replace('\\', '/'));
                if (dim != null) {
                    scanRegions(dir, dim, manifest);
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
        return manifest;
    }

    /** Every chunk of every region file of one folder; each file is opened, read and closed in turn. */
    private static void scanRegions(Path dir, String dim, Manifest manifest) throws IOException {
        java.util.List<Path> list;
        try (Stream<Path> files = Files.list(dir)) {
            list = new ArrayList<>(files.toList());
        }
        for (Path file : list) {
            Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
            if (!matcher.matches() || !Files.isRegularFile(file)) {
                continue;
            }
            int rx = Integer.parseInt(matcher.group(1));
            int rz = Integer.parseInt(matcher.group(2));
            RegionFile region = new RegionFile(file.toFile());
            try {
                if (((RegionFileAccess) (Object) region).dropin$dataFile() == null) {
                    throw new IOException("Could not open " + file);
                }
                for (int index = 0; index < 1024; index++) {
                    int cx = rx * 32 + (index & 31);
                    int cz = rz * 32 + (index >> 5);
                    NBTTagCompound tag = read(region, cx, cz);
                    if (tag != null) {
                        manifest.put(RecordKey.chunk(dim, ChunkStores.KIND, cx, cz), Manifest.hash(ChunkBytes.canonical(tag)));
                    }
                }
            } finally {
                region.close();
            }
        }
    }
}
