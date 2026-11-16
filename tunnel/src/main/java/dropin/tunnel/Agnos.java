package dropin.tunnel;

import java.nio.file.Path;

/**
 * Loader-specific answers, resolved at runtime so one jar works on Fabric and NeoForge.
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
        // NeoForge first: a Fabric compatibility layer on NeoForge would expose FabricLoader as well.
        String impl;
        if (classExists("net.neoforged.fml.loading.FMLLoader")) {
            impl = "dropin.neoforge.NeoForgePlatform";
        } else if (classExists("net.fabricmc.loader.api.FabricLoader")) {
            impl = "dropin.fabric.FabricPlatform";
        } else {
            throw new IllegalStateException("Unsupported mod loader: neither NeoForge nor Fabric was found");
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
