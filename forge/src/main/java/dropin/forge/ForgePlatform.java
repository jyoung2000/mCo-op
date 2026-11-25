package dropin.forge;

import dropin.tunnel.Agnos;
import dropin.tunnel.E4mcClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public class ForgePlatform implements Agnos.Platform {
    @Override
    public String loaderName() {
        return "forge";
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
