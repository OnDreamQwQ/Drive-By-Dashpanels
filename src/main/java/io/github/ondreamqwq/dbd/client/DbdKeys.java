package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.ondreamqwq.dbd.DbdMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key bindings.
 *
 * <p>Only one so far: opening the module picker. It lives in the vanilla controls screen, so it is
 * rebindable like any other key instead of being nailed to Alt.
 */
public final class DbdKeys {

    public static final KeyMapping PICKER = new KeyMapping(
            "key.drive_by_dashpanels.picker",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.drive_by_dashpanels");

    private DbdKeys() {
    }

    @EventBusSubscriber(modid = DbdMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {

        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(PICKER);
        }
    }
}
