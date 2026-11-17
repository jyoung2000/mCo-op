package dropin.core.mods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Starts {@link SwapHelper} as a process of its own that outlives the game (DESIGN 4.14): the helper class is
 * copied to a temp folder, so nothing of the mod jar is open in the helper, and run with the Java binary the
 * game itself runs on.
 */
public final class HelperLauncher {
    private static final String CLASS_FILE = "dropin/core/mods/SwapHelper.class";

    private HelperLauncher() {
    }

    /**
     * @param planFile the plan written by {@link SwapPlan#write}
     * @param logFile  receives the helper's output
     */
    public static Process launch(Path planFile, Path logFile) throws IOException {
        Path temp = Files.createTempDirectory("dropin-helper");
        Path classFile = temp.resolve(CLASS_FILE);
        Files.createDirectories(classFile.getParent());
        try (InputStream in = SwapHelper.class.getResourceAsStream("SwapHelper.class")) {
            if (in == null) {
                throw new IOException("the swap helper class is not readable as a resource");
            }
            Files.copy(in, classFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        ProcessBuilder builder = new ProcessBuilder(javaBinary().toString(), "-cp", temp.toString(),
                SwapHelper.class.getName(), planFile.toAbsolutePath().toString());
        builder.directory(temp.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return builder.start();
    }

    /** The running JVM's own launcher; on Windows the one without a console window when it exists. */
    static Path javaBinary() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Path windowless = bin.resolve("javaw.exe");
            return Files.isRegularFile(windowless) ? windowless : bin.resolve("java.exe");
        }
        return bin.resolve("java");
    }
}
