package dropin.tunnel;

import com.google.common.io.Files;
import dropin.mixin.tunnel.UserListAccess;
import dropin.mixin.tunnel.UserListEntryAccess;
import net.minecraft.server.management.UserList;
import net.minecraft.server.management.UserListEntry;
import net.minecraft.util.JsonUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 1.12.2 port: vanilla's {@code UserList.readSavedFile()} is {@code @SideOnly(Side.SERVER)}, so FML strips it from the
 * client and the integrated server cannot call it (NoSuchMethodError in the m0 self-test). This is its body, over
 * accessors, for PlayerListMixin's ban list and whitelist loads.
 */
public final class UserListReload {
    /** Vanilla's private {@code USER_LIST_ENTRY_TYPE}: {@code List<UserListEntry>}. */
    private static final ParameterizedType ENTRY_LIST = new ParameterizedType() {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[]{UserListEntry.class};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    };

    private UserListReload() {
    }

    public static void readSavedFile(UserList<?, ?> list) throws IOException {
        UserListAccess access = (UserListAccess) list;
        File file = access.dropin$saveFile();
        if (!file.exists()) {
            return;
        }
        Collection<UserListEntry<?>> collection;
        try (BufferedReader reader = Files.newReader(file, StandardCharsets.UTF_8)) {
            collection = JsonUtils.fromJson(access.dropin$gson(), reader, ENTRY_LIST);
        }
        if (collection != null) {
            Map<String, Object> values = access.dropin$values();
            values.clear();
            for (UserListEntry<?> entry : collection) {
                Object value = ((UserListEntryAccess) entry).dropin$getValue();
                if (value != null) {
                    values.put(access.dropin$getObjectKey(value), entry);
                }
            }
        }
    }
}
