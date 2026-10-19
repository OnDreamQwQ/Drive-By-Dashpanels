package io.github.ondreamqwq.dbd.mixin.client;

import io.github.ondreamqwq.dbd.client.PanelRemoteScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the world crosshair while the remote screen is open.
 *
 * <p>Non-pausing screens still get the HUD drawn underneath them, so the crosshair ends up painted
 * behind the panels and tinted by the screen's backdrop - it looks like a ghost. The screen draws its
 * own crosshair on top instead, where a module's own "hide the crosshair" flag can be honoured.
 */
@Mixin(Gui.class)
public class GuiCrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void dbd$hideBehindRemoteScreen(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof PanelRemoteScreen) {
            ci.cancel();
        }
    }
}
