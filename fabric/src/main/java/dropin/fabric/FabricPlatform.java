package dropin.fabric;

import dropin.tunnel.Agnos;
import dropin.tunnel.E4mcClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class FabricPlatform implements Agnos.Platform {
    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType().equals(EnvType.CLIENT);
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path jarPath() {
        return FabricLoader.getInstance().getModContainer(E4mcClient.MOD_ID).get().getOrigin().getPaths().get(0);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
