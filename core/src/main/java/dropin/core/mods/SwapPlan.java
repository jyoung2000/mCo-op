package dropin.core.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * What the swap helper has to do once the game is closed (DESIGN 4.14), written to
 * {@code config/dropin/modswap.json} and read by {@link SwapHelper}.
 *
 * @param gamePid     the game process the helper waits for
 * @param disabledDir where jars that are neither installed nor kept are moved, see {@link #disabledDir}
 * @param install     the files {@code mods/} must contain, each present in {@code storeDir} as {@code <sha512>.jar}
 * @param keep        file names in {@code mods/} that are left alone (the DropIn jar, the member's "keep" jars)
 */
public record SwapPlan(long gamePid, Path modsDir, Path storeDir, Path disabledDir, List<Install> install, List<String> keep) {
    public static final String FILE = "modswap.json";
    public static final String DISABLED_DIR = ".dropin-disabled";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public record Install(String name, String sha512) {
    }

    public SwapPlan {
        install = List.copyOf(install);
        keep = List.copyOf(keep);
    }

    /** The plan that makes {@code modsDir} hold exactly the world's mod list plus the jars to keep. */
    public static SwapPlan of(ModManifest manifest, List<String> keep, long gamePid, Path modsDir, Path storeDir, Instant now) {
        List<Install> install = manifest.files().stream().map(f -> new Install(f.path(), f.sha512())).toList();
        return new SwapPlan(gamePid, modsDir, storeDir, disabledDir(modsDir, now), install, keep);
    }

    /** {@code mods/.dropin-disabled/<UTC timestamp>}. */
    public static Path disabledDir(Path modsDir, Instant now) {
        return modsDir.resolve(DISABLED_DIR).resolve(STAMP.format(now));
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("gamePid", gamePid);
        json.addProperty("modsDir", modsDir.toAbsolutePath().toString());
        json.addProperty("storeDir", storeDir.toAbsolutePath().toString());
        json.addProperty("disabledDir", disabledDir.toAbsolutePath().toString());
        JsonArray files = new JsonArray();
        for (Install file : install) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", file.name());
            entry.addProperty("sha512", file.sha512());
            files.add(entry);
        }
        json.add("install", files);
        JsonArray kept = new JsonArray();
        keep.forEach(kept::add);
        json.add("keep", kept);
        return json.toString();
    }

    public void write(Path planFile) throws IOException {
        for (Install file : install) {
            SwapHelper.checkedName(file.name());
        }
        Files.createDirectories(planFile.getParent());
        Files.deleteIfExists(planFile.resolveSibling(SwapHelper.RESULT_FILE));
        Files.writeString(planFile, toJson(), StandardCharsets.UTF_8);
    }
}
