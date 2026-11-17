package dropin.core.manifest;

import dropin.core.crypto.Hkdf;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The current hash of every record of a world (DESIGN 4.7), as a two-level Merkle tree:
 * region hash = H(its chunk hashes in index order); root = H(all region hashes in key order, then the
 * file hashes in path order). A hash is the first 16 bytes of SHA-256, written as 32 hex characters.
 * Thread-safe.
 */
public final class Manifest {
    private static final HexFormat HEX = HexFormat.of();

    private final TreeMap<String, String> files = new TreeMap<>();
    private final TreeMap<String, TreeMap<Integer, String>> regions = new TreeMap<>();
    private final Map<String, String> regionHashes = new HashMap<>();

    /** @param fetch keys whose record must be taken from the other side, with the hash it must have */
    public record Diff(Map<String, String> fetch, List<String> delete) {
        public boolean isEmpty() {
            return fetch.isEmpty() && delete.isEmpty();
        }
    }

    public static String hash(byte[] bytes) {
        return HEX.formatHex(Hkdf.sha256(bytes), 0, 16);
    }

    public synchronized String get(String key) {
        if (RecordKey.isChunk(key)) {
            TreeMap<Integer, String> region = regions.get(RecordKey.regionOf(key));
            return region == null ? null : region.get(RecordKey.indexInRegion(key));
        }
        return files.get(key);
    }

    public synchronized void put(String key, String hash) {
        if (hash.length() != 32) {
            throw new IllegalArgumentException("Bad hash for " + key);
        }
        if (RecordKey.isChunk(key)) {
            String region = RecordKey.regionOf(key);
            regions.computeIfAbsent(region, r -> new TreeMap<>()).put(RecordKey.indexInRegion(key), hash);
            regionHashes.remove(region);
        } else if (RecordKey.isFile(key)) {
            files.put(key, hash);
        } else {
            throw new IllegalArgumentException("Not a record key: " + key);
        }
    }

    public synchronized void remove(String key) {
        if (RecordKey.isChunk(key)) {
            String region = RecordKey.regionOf(key);
            TreeMap<Integer, String> chunks = regions.get(region);
            if (chunks != null) {
                chunks.remove(RecordKey.indexInRegion(key));
                if (chunks.isEmpty()) {
                    regions.remove(region);
                }
                regionHashes.remove(region);
            }
        } else {
            files.remove(key);
        }
    }

    public synchronized int size() {
        int size = files.size();
        for (TreeMap<Integer, String> region : regions.values()) {
            size += region.size();
        }
        return size;
    }

    public synchronized boolean isEmpty() {
        return files.isEmpty() && regions.isEmpty();
    }

    /** Every record key, regions first (in key order), then files. */
    public synchronized List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Integer, String>> region : regions.entrySet()) {
            for (Integer index : region.getValue().keySet()) {
                keys.add(RecordKey.chunkInRegion(region.getKey(), index));
            }
        }
        keys.addAll(files.keySet());
        return keys;
    }

    /** The first diff level: region key to region hash, and file key to file hash. */
    public synchronized Map<String, String> top() {
        Map<String, String> top = new LinkedHashMap<>();
        for (String region : regions.keySet()) {
            top.put(region, regionHash(region));
        }
        top.putAll(files);
        return top;
    }

    /** The second diff level: chunk key to hash for one region. Empty if the region is unknown. */
    public synchronized Map<String, String> chunks(String regionKey) {
        Map<String, String> result = new LinkedHashMap<>();
        TreeMap<Integer, String> region = regions.get(regionKey);
        if (region != null) {
            for (Map.Entry<Integer, String> chunk : region.entrySet()) {
                result.put(RecordKey.chunkInRegion(regionKey, chunk.getKey()), chunk.getValue());
            }
        }
        return result;
    }

    public synchronized String root() {
        MessageDigest digest = sha256();
        for (String region : regions.keySet()) {
            digest.update(region.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(HEX.parseHex(regionHash(region)));
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(HEX.parseHex(file.getValue()));
        }
        return HEX.formatHex(digest.digest(), 0, 16);
    }

    private String regionHash(String region) {
        return regionHashes.computeIfAbsent(region, r -> {
            MessageDigest digest = sha256();
            for (Map.Entry<Integer, String> chunk : regions.get(r).entrySet()) {
                digest.update((byte) (chunk.getKey() >> 8));
                digest.update((byte) (int) chunk.getKey());
                digest.update(HEX.parseHex(chunk.getValue()));
            }
            return HEX.formatHex(digest.digest(), 0, 16);
        });
    }

    /** What {@code mine} has to fetch and delete to become {@code theirs}, descending root, region, chunk. */
    public static Diff diff(Manifest mine, Manifest theirs) {
        Map<String, String> fetch = new LinkedHashMap<>();
        List<String> delete = new ArrayList<>();
        if (mine.root().equals(theirs.root())) {
            return new Diff(fetch, delete);
        }
        Map<String, String> myTop = mine.top();
        Map<String, String> theirTop = theirs.top();
        for (Map.Entry<String, String> entry : theirTop.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue().equals(myTop.get(key))) {
                continue;
            }
            if (RecordKey.isRegion(key)) {
                Map<String, String> myChunks = mine.chunks(key);
                Map<String, String> theirChunks = theirs.chunks(key);
                theirChunks.forEach((chunk, hash) -> {
                    if (!hash.equals(myChunks.get(chunk))) {
                        fetch.put(chunk, hash);
                    }
                });
                myChunks.keySet().stream().filter(chunk -> !theirChunks.containsKey(chunk)).forEach(delete::add);
            } else {
                fetch.put(key, entry.getValue());
            }
        }
        for (String key : myTop.keySet()) {
            if (!theirTop.containsKey(key)) {
                if (RecordKey.isRegion(key)) {
                    delete.addAll(mine.chunks(key).keySet());
                } else {
                    delete.add(key);
                }
            }
        }
        return new Diff(fetch, delete);
    }

    /** Writes {@code key<TAB>hash} lines, through a temp file and an atomic move. */
    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (String key : keys()) {
                writer.write(key);
                writer.write('\t');
                writer.write(get(key));
                writer.write('\n');
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** An empty manifest if the file does not exist. */
    public static Manifest load(Path file) throws IOException {
        Manifest manifest = new Manifest();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.lastIndexOf('\t');
                if (tab > 0) {
                    manifest.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return manifest;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
