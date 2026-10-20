package io.github.ondreamqwq.dbd.client;

import io.github.ondreamqwq.dbd.DbdMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Draws the remote's top layer once the screen itself is finished.
 *
 * <p>A screen cannot reliably put something on top of a 3D model drawn inside it: the model's vertices may
 * still be sitting in a render type's buffer and only reach the framebuffer at the end of the frame, after
 * the screen returned. Hooking the tail of the render pass is the one place where "after the models" is
 * guaranteed rather than hoped for.
 */
@EventBusSubscriber(modid = DbdMod.MOD_ID, value = Dist.CLIENT)
public final class PanelRemoteOverlay {
    private PanelRemoteOverlay() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof PanelRemoteScreen screen)) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        if (graphics == null) {
            return;
        }
        screen.renderTopLayer(graphics, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == screen) {
            minecraft.renderBuffers().bufferSource().endBatch();
        }
    }
}