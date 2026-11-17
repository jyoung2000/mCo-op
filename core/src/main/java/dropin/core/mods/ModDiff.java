package dropin.core.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A local {@code mods/} folder against the world's mod list (DESIGN 4.14).
 *
 * @param toDownload manifest files that are not in the folder (same name and same SHA-512)
 * @param toRemove   local jars that are not in the manifest and not marked "keep"; the helper moves them away
 * @param keptExtras local jars that are not in the manifest but marked "keep": the member may join, not host
 */
public record ModDiff(List<ModManifest.Entry> toDownload, List<ModManifest.Entry> toRemove, List<ModManifest.Entry> keptExtras) {
    /**
     * @param local the scan of the folder ({@link ModScan#scan})
     * @param keep  file names the member wants to keep, compared without case
     */
    public static ModDiff of(ModManifest wanted, List<ModManifest.Entry> local, Set<String> keep) {
        Set<String> kept = keep.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<ModManifest.Entry> toDownload = new ArrayList<>();
        List<ModManifest.Entry> toRemove = new ArrayList<>();
        List<ModManifest.Entry> keptExtras = new ArrayList<>();
        for (ModManifest.Entry file : wanted.files()) {
            if (local.stream().noneMatch(have -> same(have, file))) {
                toDownload.add(file);
            }
        }
        for (ModManifest.Entry have : local) {
            if (wanted.files().stream().noneMatch(file -> same(have, file))) {
                (kept.contains(have.path().toLowerCase(Locale.ROOT)) ? keptExtras : toRemove).add(have);
            }
        }
        return new ModDiff(List.copyOf(toDownload), List.copyOf(toRemove), List.copyOf(keptExtras));
    }

    private static boolean same(ModManifest.Entry a, ModManifest.Entry b) {
        return a.path().equalsIgnoreCase(b.path()) && a.sha512().equals(b.sha512());
    }

    /** The folder has everything the world needs and nothing else, except jars the member keeps. */
    public boolean mayJoin() {
        return toDownload.isEmpty() && toRemove.isEmpty();
    }

    /** A host must have exactly the world's mods: kept extras would change the world for everyone. */
    public boolean mayHost() {
        return mayJoin() && keptExtras.isEmpty();
    }

    public long downloadBytes() {
        return toDownload.stream().mapToLong(ModManifest.Entry::size).sum();
    }
}
