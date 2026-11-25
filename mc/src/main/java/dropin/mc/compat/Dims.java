package dropin.mc.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * 1.12.2 port: dimensions are numbers here (0 overworld, -1 nether, 1 end, others from mods), not resource ids.
 * Records name a dimension by the id core knows ({@code dropin.core.manifest.RecordKey}): the three vanilla ids,
 * and {@code forge:dim<N>} for any other dimension, whose chunks the game keeps in {@code DIM<N>/} (Forge's
 * default {@code WorldProvider.getSaveFolder()}).
 */
public final class Dims {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";
    private static final String FORGE_PREFIX = "forge:dim";

    private Dims() {
    }

    public static String idOf(int dimension) {
        return switch (dimension) {
            case 0 -> OVERWORLD;
            case -1 -> NETHER;
            case 1 -> END;
            default -> FORGE_PREFIX + dimension;
        };
    }

    public static String idOf(World world) {
        return idOf(world.provider.getDimension());
    }

    /** The dimension number of a record-key dimension id, or null if it is not one of 1.12.2's. */
    public static Integer numberOf(String id) {
        switch (id) {
            case OVERWORLD:
                return 0;
            case NETHER:
                return -1;
            case END:
                return 1;
            default:
                if (id.startsWith(FORGE_PREFIX)) {
                    try {
                        int n = Integer.parseInt(id.substring(FORGE_PREFIX.length()));
                        return n == 0 || n == -1 || n == 1 ? null : n;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
        }
    }

    /** The server's world of a record-key dimension id, or null if that dimension is not loaded. */
    public static WorldServer world(MinecraftServer server, String id) {
        Integer n = numberOf(id);
        return n == null ? null : net.minecraftforge.common.DimensionManager.getWorld(n);
    }
}
