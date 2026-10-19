package io.github.ondreamqwq.dbd.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import io.github.ondreamqwq.dbd.client.WireSelection;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import moth.boxxed.panels.api.panel.AbstractPanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;

/**
 * Draws the "this module is wired" outline.
 *
 * <p>The outline is hooked onto the module render call instead of onto the end of
 * {@code renderModules}, because the pose at that point is already the one Dashpanels uses for the
 * module itself (panel transform plus the per-module rotation), and {@link Module#renderOutline}
 * expects exactly that. Reproducing the transform by hand would drift from theirs.
 *
 * <p>Two details make the frame readable next to Dashpanels' own white hover outline, which is drawn
 * on exactly the same geometry:
 * <ul>
 *   <li>the frame is inflated by a hair around the module's own centre, so the two outlines sit at
 *       slightly different depths instead of fighting for the same pixels (that fight is what looked
 *       like flickering);</li>
 *   <li>the line width is raised for the whole panel and the line batch is flushed once on the way
 *       out, because line width is a draw-time state: setting it around a single call does nothing
 *       unless the vertices are flushed while it is still raised. Flushing per module instead would
 *       mean one full batch break per module per frame, which is the expensive way to get the same
 *       picture.</li>
 * </ul>
 */
@Mixin(value = AbstractPanelRenderer.class, remap = false)
public abstract class AbstractPanelRendererMixin {

    /** How far the frame is pushed out, as a fraction of the module box. Small enough to still hug it. */
    private static final float INFLATE = 1.02F;

    private static final float LINE_WIDTH = 2.5F;

    /** Line width is a draw-time state, so it has to cover the draw call for the whole panel. */
    @Inject(method = "renderModules", at = @At("HEAD"), remap = false)
    private void dbd$widenLines(AbstractPanelBlockEntity panel, float partialTick, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        RenderSystem.lineWidth(LINE_WIDTH);
    }

    /** One flush for every outline of this panel, then put the shared state back. */
    @Inject(method = "renderModules", at = @At("RETURN"), remap = false)
    private void dbd$flushLines(AbstractPanelBlockEntity panel, float partialTick, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch(RenderType.lines());
        RenderSystem.lineWidth(1.0F);
    }

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
        int color = WireSelection.frameColor(panel, module);
        if (color == 0) {
            return;
        }

        AABB bounds = module.getVoxelShape().bounds();
        double cx = (bounds.minX + bounds.maxX) / 2.0D;
        double cy = (bounds.minY + bounds.maxY) / 2.0D;
        double cz = (bounds.minZ + bounds.maxZ) / 2.0D;

        pose.pushPose();
        pose.translate(cx, cy, cz);
        pose.scale(INFLATE, INFLATE, INFLATE);
        pose.translate(-cx, -cy, -cz);
        module.renderOutline(pose, buffer, partialTick, color);
        pose.popPose();
    }
}
