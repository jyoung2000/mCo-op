package dropin.neoforge;

import dropin.tunnel.Agnos;
import dropin.tunnel.E4mcClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class NeoForgePlatform implements Agnos.Platform {
    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path jarPath() {
        return FMLLoader.getLoadingModList().getModFileById(E4mcClient.MOD_ID).getFile().getFilePath();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }
}
