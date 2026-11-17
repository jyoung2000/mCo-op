package dropin.core.manifest;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keys of replicated records (DESIGN 4.7).
 * <ul>
 * <li>chunk record: {@code c|<dimension id>|<kind>|<cx>|<cz>}, kind one of region, entities, poi</li>
 * <li>file record: {@code f|<path relative to the world root, with forward slashes>}</li>
 * <li>region (a group of up to 1024 chunk records): {@code r|<dimension id>|<kind>|<rx>|<rz>}</li>
 * </ul>
 */
public final class RecordKey {
    public static final Set<String> KINDS = Set.of("region", "entities", "poi");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

    /** @param dim dimension id, for example {@code minecraft:overworld} */
    public record Chunk(String dim, String kind, int cx, int cz) {
    }

    private RecordKey() {
    }

    public static String chunk(String dim, String kind, int cx, int cz) {
        return "c|" + dim + "|" + kind + "|" + cx + "|" + cz;
    }

    public static String file(String path) {
        return "f|" + path;
    }

    public static boolean isChunk(String key) {
        return key.startsWith("c|");
    }

    public static boolean isFile(String key) {
        return key.startsWith("f|");
    }

    public static boolean isRegion(String key) {
        return key.startsWith("r|");
    }

    public static String filePath(String key) {
        return key.substring(2);
    }

    /** @throws IllegalArgumentException if the key is not a well-formed chunk key */
    public static Chunk parseChunk(String key) {
        String[] parts = key.split("\\|", -1);
        if (parts.length != 5 || !parts[0].equals("c") || !KINDS.contains(parts[2]) || !validDimension(parts[1])) {
            throw new IllegalArgumentException("Not a chunk key: " + key);
        }
        try {
            return new Chunk(parts[1], parts[2], Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a chunk key: " + key);
        }
    }

    public static boolean validDimension(String dim) {
        return DIMENSION.matcher(dim).matches() && !dim.contains("..");
    }

    /** The region a chunk key belongs to. */
    public static String regionOf(String chunkKey) {
        Chunk chunk = parseChunk(chunkKey);
        return "r|" + chunk.dim() + "|" + chunk.kind() + "|" + (chunk.cx() >> 5) + "|" + (chunk.cz() >> 5);
    }

    /** Position of a chunk inside its region file: {@code (cx & 31) + (cz & 31) * 32}. */
    public static int indexInRegion(String chunkKey) {
        Chunk chunk = parseChunk(chunkKey);
        return (chunk.cx() & 31) + (chunk.cz() & 31) * 32;
    }

    public static String chunkInRegion(String regionKey, int index) {
        String[] parts = regionKey.split("\\|", -1);
        if (parts.length != 5 || !parts[0].equals("r")) {
            throw new IllegalArgumentException("Not a region key: " + regionKey);
        }
        int rx = Integer.parseInt(parts[3]);
        int rz = Integer.parseInt(parts[4]);
        return chunk(parts[1], parts[2], rx * 32 + (index & 31), rz * 32 + (index >> 5));
    }

    /**
     * Whether a file of the world folder is replicated as a file record (DESIGN 4.7). Also the safety check
     * on the receiving side: a path that is absolute, escapes the folder or is not on the list is refused.
     */
    public static boolean fileAllowed(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        String last = segments[segments.length - 1];
        if (segments[0].equals("dropin") || last.equals("session.lock") || last.endsWith(".tmp")
                || path.equals("level.dat_old")) {
            return false;
        }
        if (segments.length == 1) {
            return path.equals("level.dat") || path.equals("icon.png");
        }
        switch (segments[0]) {
            case "data", "players", "datapacks", "resourcepacks":
                return true;
            case "dimensions":
                // dimensions/<namespace>/<name...>/data/**, never the three region-type folders
                for (int i = 1; i < segments.length - 1; i++) {
                    if (KINDS.contains(segments[i])) {
                        return false;
                    }
                    if (i >= 3 && segments[i].equals("data")) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }
}
