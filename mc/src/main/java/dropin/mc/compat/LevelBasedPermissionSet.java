package dropin.mc.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.UserListOpsEntry;

/**
 * The game of this version (1.21.1 and 1.12.2 alike) expresses command rights as an operator level (0-4), not as
 * the permission sets of 26.x. These are the levels the 26.x sets stand for, under the same names.
 */
public final class LevelBasedPermissionSet {
    public static final Integer ALL = 0;
    public static final Integer GAMEMASTER = 2;
    public static final Integer ADMIN = 3;
    public static final Integer OWNER = 4;

    private LevelBasedPermissionSet() {
    }

    /**
     * The operator level the game gives a player, DropIn's policy included (1.21.1 / 26.x
     * {@code MinecraftServer.getProfilePermissions}). 1.12.2 port: the game has no such method; vanilla's level is
     * what {@code EntityPlayerMP.canUseCommand} works out: 0 without {@code PlayerList.canSendCommands}, else the
     * level of the player's ops.json entry, else the server's op level. DESIGN 4.13's levels come first
     * ({@code IntegratedServerPermissionsMixin}, {@code PlayerListPermissionsMixin}).
     */
    public static int of(MinecraftServer server, GameProfile profile) {
        Integer policy = dropin.mc.host.HostSync.permissions(server, profile.getId());
        if (policy != null) {
            return policy;
        }
        if (!server.getPlayerList().canSendCommands(profile)) {
            return 0;
        }
        UserListOpsEntry entry = server.getPlayerList().getOppedPlayers().getEntry(profile);
        return entry != null ? entry.getPermissionLevel() : server.getOpPermissionLevel();
    }
}
