package dropin.mc.compat;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.network.chat.Component;

/** 1.20.1 port: the later {@code GenericMessageScreen} is {@code GenericDirtMessageScreen} in this version. */
public class GenericMessageScreen extends GenericDirtMessageScreen {
    public GenericMessageScreen(Component title) {
        super(title);
    }
}
