package dropin.mixin.mc;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 1.12.2 port: the server's connections (26.x {@code ServerConnectionListener.getConnections()}), so a host that
 * stops can close the members' game connections once its last checkpoint is out (HostSync.closeMemberConnections).
 * The list is a {@code Collections.synchronizedList}.
 */
@Mixin(NetworkSystem.class)
public interface NetworkSystemAccess {
    @Accessor("networkManagers")
    List<NetworkManager> dropin$networkManagers();
}
