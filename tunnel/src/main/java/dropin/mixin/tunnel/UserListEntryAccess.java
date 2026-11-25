package dropin.mixin.tunnel;

import net.minecraft.server.management.UserListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 1.12.2 port: UserListEntry.getValue() is package-private; UserListMixin needs it. */
@Mixin(UserListEntry.class)
public interface UserListEntryAccess {
    @Invoker("getValue")
    Object dropin$getValue();
}
