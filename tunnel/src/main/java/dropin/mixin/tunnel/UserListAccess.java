package dropin.mixin.tunnel;

import com.google.gson.Gson;
import net.minecraft.server.management.UserList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.File;
import java.util.Map;

/** 1.12.2 port: the UserList members {@link dropin.tunnel.UserListReload} needs. */
@Mixin(UserList.class)
public interface UserListAccess {
    @Accessor("gson")
    Gson dropin$gson();

    @Accessor("saveFile")
    File dropin$saveFile();

    @Accessor("values")
    Map<String, Object> dropin$values();

    @Invoker("getObjectKey")
    String dropin$getObjectKey(Object value);
}
