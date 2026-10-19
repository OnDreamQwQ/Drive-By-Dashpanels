package io.github.ondreamqwq.dbd.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.ondreamqwq.dbd.content.PanelBindings;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import moth.boxxed.panels.api.panel.AbstractPanelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Draws the "this module is wired" outline.
 *
 * <p>The outline is hooked onto the module render call instead of onto the end of
 * {@code renderModules}, because the pose at that point is already the one Dashpanels uses for the
 * module itself (panel transform plus the per-module rotation), and {@link Module#renderOutline}
 * expects exactly that. Reproducing the transform by hand would drift from theirs.
 */
@Mixin(value = AbstractPanelRenderer.class, remap = false)
public abstract class AbstractPanelRendererMixin {

    private static final int WIRED_COLOR = 0xFFFFC400;

    @Redirect(
            method = "renderModules",
            at = @At(
                    value = "INVOKE",
                    target = "Lmoth/boxxed/panels/api/module/Module;render("
                            + "Lmoth/boxxed/panels/api/panel/AbstractPanelBlockEntity;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;F"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
                    remap = false),
            remap = false)
    private void dbd$outlineWiredModules(Module module, AbstractPanelBlockEntity panel, PoseStack pose,
            float partialTick, MultiBufferSource buffer, int light, int overlay) {
        module.render(panel, pose, partialTick, buffer, light, overlay);
        if (PanelBindings.get(panel, module.getName()) != null) {
            module.renderOutline(pose, buffer, partialTick, WIRED_COLOR);
        }
    }
}
