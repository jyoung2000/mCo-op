package dropin.core.manifest;

import java.util.Locale;
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
    /** Largest file of a mod's own folder in the world's root that is replicated (bytes). */
    public static final long MAX_MOD_FILE = 64L * 1024 * 1024;
    private static final Pattern RESERVED = Pattern.compile("CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³]|CONIN\\$|CONOUT\\$");
    private static final Pattern DIMENSION =Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

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
        if (!allowedExact(path)) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (!portableSegment(segment)) {
                return false;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.equals(path)) {
            // NTFS and APFS ignore case, so "Dropin/ledger.jsonl", "r.0.0.MCA" or "Level.dat" would reach the
            // file the exact name is refused or treated specially for. The game itself writes only lower case.
            String[] lowerSegments = lower.split("/", -1);
            if (!allowedExact(lower) || gameFile(lowerSegments)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A segment Windows stores under the name it is given: not ending in a dot or a space (Windows drops
     * those, so "dropin." is "dropin") and not a reserved device name (CON, NUL, COM1 and so on, with or
     * without an extension).
     */
    private static boolean portableSegment(String segment) {
        char end = segment.charAt(segment.length() - 1);
        if (end == '.' || end == ' ') {
            return false;
        }
        int dot = segment.indexOf('.');
        String stem = (dot < 0 ? segment : segment.substring(0, dot)).toUpperCase(Locale.ROOT).strip();
        return !RESERVED.matcher(stem).matches();
    }

    private static boolean allowedExact(String path) {
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
        if (segments[0].equals("dimensions")) {
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
        }
        if (gameFile(segments)) {
            return true;
        }
        // Anything else in the world's root is what a mod keeps there (for example serverconfig/). Region
        // files are never file records: they may be open, and chunks travel as chunk records.
        return !KINDS.contains(segments[0]) && !last.endsWith(".mca") && !last.endsWith(".mcc");
    }

    /** The files the game itself keeps in a world's root. */
    private static boolean gameFile(String[] segments) {
        if (segments.length == 1) {
            return segments[0].equals("level.dat") || segments[0].equals("icon.png");
        }
        return switch (segments[0]) {
            case "data", "players", "datapacks", "resourcepacks", "dimensions" -> true;
            default -> false;
        };
    }

    /**
     * {@link #fileAllowed(String)} for a file of a known size: a file a mod keeps in the world's root is
     * replicated only up to {@link #MAX_MOD_FILE} bytes (a backup folder must not travel with every world).
     * The host's scan and a member's scan both use this, so they agree on what a copy holds.
     */
    public static boolean fileAllowed(String path, long size) {
        return fileAllowed(path) && (size <= MAX_MOD_FILE || gameFile(path.split("/", -1)));
    }
}
