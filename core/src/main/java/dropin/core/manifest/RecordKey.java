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
    /** 1.12.2 (Forge): a mod's dimension N, kept in {@code DIM<N>} (WorldProvider.getSaveFolder). */
    private static final Pattern FORGE_DIM = Pattern.compile("forge:dim(-?[1-9][0-9]*)");
    private static final Pattern FORGE_DIM_FOLDER = Pattern.compile("DIM(-?[1-9][0-9]*)");

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

    /**
     * The folder, relative to the world root, that holds a dimension's region-type folders and its saved data in
     * the world layout of Minecraft 1.21.1 and older: the overworld uses the root itself (""), the Nether
     * {@code DIM-1}, the End {@code DIM1}, any other dimension {@code dimensions/<namespace>/<path>}.
     *
     * @throws IllegalArgumentException if the dimension id is not valid
     */
    public static String storageFolder(String dim) {
        if (!validDimension(dim)) {
            throw new IllegalArgumentException("Not a dimension id: " + dim);
        }
        return switch (dim) {
            case "minecraft:overworld" -> "";
            case "minecraft:the_nether" -> "DIM-1";
            case "minecraft:the_end" -> "DIM1";
            default -> {
                Integer forge = forgeDimension(FORGE_DIM.matcher(dim));
                yield forge != null ? "DIM" + forge : "dimensions/" + dim.replaceFirst(":", "/");
            }
        };
    }

    /**
     * 1.12.2 (Forge) only: dimension N other than -1, 0 and 1 is {@code forge:dim<N>} and its folder is
     * {@code DIM<N>}, Forge's {@code WorldProvider.getSaveFolder()}. Later versions never make such a folder.
     */
    private static Integer forgeDimension(java.util.regex.Matcher matcher) {
        if (!matcher.matches()) {
            return null;
        }
        try {
            int n = Integer.parseInt(matcher.group(1));
            return n == -1 || n == 1 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The folder name of a 1.12.2 dimension: DIM-1, DIM1 or DIM&lt;N&gt; of a mod's dimension (exact case). */
    private static boolean legacyDimensionFolder(String segment) {
        return segment.equals("DIM-1") || segment.equals("DIM1") || forgeDimension(FORGE_DIM_FOLDER.matcher(segment)) != null;
    }

    /**
     * The dimension whose storage folder ({@link #storageFolder(String)}) a path relative to the world root is,
     * with forward slashes, or null if it is none.
     */
    public static String dimensionOfFolder(String relative) {
        switch (relative) {
            case "":
                return "minecraft:overworld";
            case "DIM-1":
                return "minecraft:the_nether";
            case "DIM1":
                return "minecraft:the_end";
            default:
                break;
        }
        Integer forge = forgeDimension(FORGE_DIM_FOLDER.matcher(relative));
        if (forge != null) {
            return "forge:dim" + forge;
        }
        String[] segments = relative.split("/", -1);
        if (segments.length < 3 || !segments[0].equals("dimensions")) {
            return null;
        }
        String dim = segments[1] + ":" + String.join("/", java.util.Arrays.asList(segments).subList(2, segments.length));
        return validDimension(dim) && storageFolder(dim).equals(relative) ? dim : null;
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
        String lower = canonicalCase(path);
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
     * The path as the game would spell it: lower case, except the dimension folders, which the game names
     * {@code DIM-1} and {@code DIM1} (and, on 1.12.2, {@code DIM<N>} for a mod's dimension). A path that differs
     * from this only in case reaches the same file on a case-insensitive file system.
     */
    private static String canonicalCase(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int slash = lower.indexOf('/');
        if (slash > 0 && legacyDimensionFolder(lower.substring(0, slash).toUpperCase(Locale.ROOT))) {
            return lower.substring(0, slash).toUpperCase(Locale.ROOT) + lower.substring(slash);
        }
        return lower;
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
        if (legacyDimensionFolder(segments[0].toUpperCase(Locale.ROOT))) {
            // 1.21.1 and older: the Nether's and the End's saved data, DIM-1/data/** and DIM1/data/**; never their
            // region-type folders. 1.12.2 also: a mod's dimension DIM<N>/data/**, and Forge's forcedchunks.dat,
            // which ForgeChunkManager keeps in every dimension's folder.
            return segments.length > 2 && segments[1].equals("data")
                    || segments.length == 2 && segments[1].equals("forcedchunks.dat");
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
            // playerdata, advancements, stats, DIM-1 and DIM1: the world layout of 1.21.1 and older
            case "data", "players", "datapacks", "resourcepacks", "dimensions",
                 "playerdata", "advancements", "stats", "DIM-1", "DIM1" -> true;
            // 1.12.2: a mod's dimension folder DIM<N>
            default -> legacyDimensionFolder(segments[0]);
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
