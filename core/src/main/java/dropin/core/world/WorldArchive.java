package dropin.core.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Brings a world from a folder or a .zip into the saves folder (DESIGN 4.4 "From a folder or .zip").
 * A zip is checked completely before anything is written: every entry must stay inside the target folder,
 * and {@code level.dat} must be at the root or inside a single top-level folder.
 */
public final class WorldArchive {
    private static final String LEVEL_DAT = "level.dat";
    private static final int MAX_ENTRIES = 500_000;

    public enum Reason {
        /** An entry would be written outside the target folder. */
        PATH_ESCAPE,
        /** No {@code level.dat} at the root or inside a single top-level folder. */
        NO_LEVEL_DAT,
        /** Not a readable zip file or folder. */
        UNREADABLE
    }

    public static final class WorldImportException extends Exception {
        private final Reason reason;

        public WorldImportException(Reason reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private WorldArchive() {
    }

    /** Extracts a zipped world into a new folder under {@code savesDir} and returns that folder. */
    public static Path extractZip(Path zip, Path savesDir) throws IOException, WorldImportException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Path probe = savesDir.resolve("probe").toAbsolutePath().normalize();
            List<ZipEntry> entries = new ArrayList<>();
            Set<String> topLevel = new HashSet<>();
            boolean rootLevelDat = false;
            Enumeration<? extends ZipEntry> all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entries.size() >= MAX_ENTRIES) {
                    throw new WorldImportException(Reason.UNREADABLE, "too many entries");
                }
                if (name.startsWith("/") || name.matches("^[A-Za-z]:.*") || !resolveInside(probe, name)) {
                    throw new WorldImportException(Reason.PATH_ESCAPE, name);
                }
                entries.add(entry);
                String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (!first.isEmpty()) {
                    topLevel.add(first);
                }
                if (name.equals(LEVEL_DAT)) {
                    rootLevelDat = true;
                }
            }
            String strip;
            if (rootLevelDat) {
                strip = "";
            } else if (topLevel.size() == 1 && file.getEntry(topLevel.iterator().next() + "/" + LEVEL_DAT) != null) {
                strip = topLevel.iterator().next() + "/";
            } else {
                throw new WorldImportException(Reason.NO_LEVEL_DAT, zip.getFileName().toString());
            }

            String base = strip.isEmpty() ? stripExtension(zip.getFileName().toString()) : strip.substring(0, strip.length() - 1);
            Path target = uniqueFolder(savesDir, base);
            Files.createDirectories(target);
            Path root = target.toAbsolutePath().normalize();
            for (ZipEntry entry : entries) {
                String name = entry.getName().replace('\\', '/').substring(strip.length());
                if (name.isEmpty() || name.equals("session.lock")) {
                    continue;
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new WorldImportException(Reason.PATH_ESCAPE, entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, out);
                    }
                }
            }
            return target;
        } catch (ZipException e) {
            throw new WorldImportException(Reason.UNREADABLE, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Writes a closed world folder as a zip that {@link #extractZip} takes back: one top folder named after the
     * world folder, without the co-op folder ({@code dropin/}) and without {@code session.lock}, so the result
     * is an ordinary world. Returns the number of files written.
     */
    public static int exportZip(Path worldFolder, Path zip) throws IOException {
        Path root = worldFolder.toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve(LEVEL_DAT))) {
            throw new IOException("not a world folder: " + root.getFileName());
        }
        Path target = zip.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        String top = root.getFileName() + "/";
        int[] count = {0};
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    boolean coop = root.equals(dir.getParent()) && dir.getFileName().toString().equals(CoopWorldStore.MARKER_DIR);
                    return coop ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    if (!file.getFileName().toString().equals("session.lock")) {
                        out.putNextEntry(new ZipEntry(top + root.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, out);
                        out.closeEntry();
                        count[0]++;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return count[0];
    }

    /** Copies a world folder into a new folder under {@code savesDir} and returns that folder. */
    public static Path copyFolder(Path folder, Path savesDir) throws IOException, WorldImportException {
        if (!Files.isDirectory(folder)) {
            throw new WorldImportException(Reason.UNREADABLE, folder.getFileName().toString());
        }
        if (!Files.isRegularFile(folder.resolve(LEVEL_DAT))) {
            throw new WorldImportException(Reason.NO_LEVEL_DAT, folder.getFileName().toString());
        }
        Path target = uniqueFolder(savesDir, folder.getFileName().toString());
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(folder.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = folder.relativize(file);
                if (!relative.toString().equals("session.lock")) {
                    Files.copy(file, target.resolve(relative.toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }

    private static boolean resolveInside(Path root, String name) {
        try {
            Path resolved = root.resolve(name).normalize();
            return resolved.startsWith(root);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A folder under {@code savesDir} that does not exist yet, named after {@code base}. */
    static Path uniqueFolder(Path savesDir, String base) {
        String clean = base.replaceAll("[^A-Za-z0-9 _.()\\-]", "_").strip();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty()) {
            clean = "World";
        }
        Path candidate = savesDir.resolve(clean);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = savesDir.resolve(clean + " (" + i + ")");
        }
        return candidate;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
