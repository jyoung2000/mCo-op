package dropin.mc.compat;

/**
 * 1.21.1 port: 26.x has {@code MinecraftServer.setForceGameMode(boolean)}; in this version a published
 * integrated server always forces its game type. {@code IntegratedServerGameModeMixin} adds the switch.
 */
public interface ForceGameModeAccess {
    void dropin$setForceGameMode(boolean force);
}
