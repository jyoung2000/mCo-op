package dropin.tunnel;

import java.nio.file.Path;

/**
 * Loader-specific answers, resolved at runtime (26.x: one jar for Fabric and NeoForge; 1.12.2: Cleanroom).
 * (Upstream resolved this at build time with Architectury's {@code @ExpectPlatform}.)
 */
public class Agnos {
    public interface Platform {
        String loaderName();

        boolean isClient();

        Path configDir();

        Path jarPath();

        boolean isDevelopmentEnvironment();
    }

    private static final Platform PLATFORM = detect();

    private static Platform detect() {
        // 1.12.2 port: Cleanroom (a Forge 1.12.2 fork) is the only loader.
        String impl;
        if (classExists("net.minecraftforge.fml.common.Loader")) {
            impl = "dropin.cleanroom.CleanroomPlatform";
        } else {
            throw new IllegalStateException("Unsupported mod loader: Cleanroom (Forge 1.12.2) was not found");
        }
        try {
            return (Platform) Class.forName(impl, true, Agnos.class.getClassLoader()).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not load platform support " + impl, e);
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, Agnos.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static String loaderName() {
        return PLATFORM.loaderName();
    }

    public static boolean isClient() {
        return PLATFORM.isClient();
    }

    public static Path configDir() {
        return PLATFORM.configDir();
    }

    public static Path jarPath() {
        return PLATFORM.jarPath();
    }

    public static boolean isDevelopmentEnvironment() {
        return PLATFORM.isDevelopmentEnvironment();
    }
}
