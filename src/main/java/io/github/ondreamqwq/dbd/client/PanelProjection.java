package io.github.ondreamqwq.dbd.client;

import java.util.Map;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * The orthographic camera used by {@link PanelRemoteScreen}.
 *
 * <p>Two coordinate spaces matter here and they are not the same one:
 *
 * <ul>
 *   <li><b>module space</b> - the flat board the modules live on, always lying in the XZ plane with
 *       +Y up. Modules are placed at {@code getPos() / 16}, and their voxel shapes live in this
 *       space, which is why the click test has to run here.</li>
 *   <li><b>block space</b> - the unit cube the block entity renderer expects. Dashpanels converts
 *       module space into it with {@link AbstractPanelBlockEntity#transformPanelClipping}, so this
 *       class uses that very method instead of guessing per panel type.</li>
 * </ul>
 *
 * <p>The camera is built module space to screen, then composed with the inverse of the clipping
 * transform to obtain the matrix the renderer needs. That is what keeps the picture and the click
 * test in agreement for floor, wall and ceiling panels alike.
 */
public final class PanelProjection {

    /** How far the camera is tilted off the panel's working face. */
    public static final float TILT_DEGREES = 45.0F;

    /**
     * Depth range handed to {@link Matrix4f#invert} when unprojecting a mouse position.
     *
     * <p>Any two distinct values work: the camera is orthographic, so a mouse position maps to a
     * line, and both ends only have to be far enough apart to cross the whole panel.
     */
    private static final float DEPTH = 4000.0F;

    private PanelProjection() {
    }

    /**
     * The area the modules occupy, in module space.
     *
     * <p>Panels with no modules yet fall back to their declared content area, so the screen still
     * frames something instead of dividing by zero.
     */
    public static AABB bounds(AbstractPanelBlockEntity panel) {
        AABB box = null;
        for (Map.Entry<String, Module> entry : panel.getModules().asEntryList()) {
            Module module = entry.getValue();
            VoxelShape shape = module.getVoxelShape();
            if (shape == null || shape.isEmpty()) {
                continue;
            }
            AABB moved = shape
                    .move(module.getPos().x / 16.0D, 0.0D, module.getPos().y / 16.0D)
                    .bounds();
            box = box == null ? moved : box.minmax(moved);
        }
        if (box == null) {
            var area = panel.getContentArea();
            box = new AABB(0.0D, 0.0D, 0.0D, area.x / 16.0D, 0.125D, area.y / 16.0D);
        }
        return box;
    }

    /** Module space to block space, straight from the panel itself. */
    public static Matrix4f clipping(AbstractPanelBlockEntity panel) {
        PoseStack stack = new PoseStack();
        stack.pushPose();
        panel.transformPanelClipping(stack);
        return new Matrix4f(stack.last().pose());
    }

    /**
     * Module space to screen, as an orthographic camera tilted {@link #TILT_DEGREES} off the board.
     *
     * <p>The y scale is negative because screen pixels grow downward while the board grows upward.
     * Fitting the content box rather than the unit cube matters: a fresh panel is mostly empty, and
     * scaling to a fixed cube would leave the modules as a speck in the middle.
     */
    public static Matrix4f camera(AABB box, float centreX, float centreY, float scale) {
        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = (box.minY + box.maxY) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;
        return new Matrix4f()
                .translate(centreX, centreY, 0.0F)
                .scale(scale, -scale, scale)
                .rotateX((float) Math.toRadians(-TILT_DEGREES))
                .translate((float) -cx, (float) -cy, (float) -cz);
    }

    /** A scale that fits {@code box} into the given pixel box, tilt included. */
    public static float fitScale(AABB box, float pixelWidth, float pixelHeight) {
        double cos = Math.cos(Math.toRadians(TILT_DEGREES));
        double sin = Math.sin(Math.toRadians(TILT_DEGREES));
        double width = Math.max(0.05D, box.getXsize());
        double height = Math.max(0.05D, box.getYsize() * cos + box.getZsize() * sin);
        return (float) Math.min(pixelWidth / width, pixelHeight / height);
    }

    /** The module under {@code (mouseX, mouseY)}, or {@code null} when the ray hits nothing. */
    public static Module pick(AbstractPanelBlockEntity panel, Matrix4f camera,
                              double mouseX, double mouseY) {
        Matrix4f inverse = new Matrix4f(camera).invert();
        var near = inverse.transformPosition(new org.joml.Vector3f((float) mouseX, (float) mouseY, -DEPTH));
        var far = inverse.transformPosition(new org.joml.Vector3f((float) mouseX, (float) mouseY, DEPTH));
        Vec3 from = new Vec3(near.x, near.y, near.z);
        Vec3 to = new Vec3(far.x, far.y, far.z);

        Module best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, Module> entry : panel.getModules().asEntryList()) {
            Module module = entry.getValue();
            VoxelShape shape = module.getVoxelShape();
            if (shape == null || shape.isEmpty()) {
                continue;
            }
            BlockHitResult hit = shape
                    .move(module.getPos().x / 16.0D, 0.0D, module.getPos().y / 16.0D)
                    .clip(from, to, BlockPos.ZERO);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            double distance = hit.getLocation().distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = module;
            }
        }
        return best;
    }
}
