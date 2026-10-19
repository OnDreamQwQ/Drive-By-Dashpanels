package io.github.ondreamqwq.dbd.client;

import java.util.Map;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The orthographic camera used by {@link PanelRemoteScreen}.
 *
 * <p>The screen renders the real panel block entity itself, with its own renderer, through one
 * matrix. The same matrix is then inverted to turn a mouse position into a ray, which is clipped
 * against each module's voxel shape - so what the player sees and what the player clicks can never
 * drift apart. That is the one thing the old panel-viewer approach got wrong: it drew an
 * approximation and hit-tested against a different approximation.
 */
public final class PanelProjection {

    /** How far the camera is tilted off the panel's working face. */
    public static final float TILT_DEGREES = 45.0F;

    /**
     * Depth range handed to {@link Matrix4f#invert} when unprojecting a mouse position.
     *
     * <p>Any two distinct values work: the camera is orthographic, so a mouse position maps to a
     * line, and both ends of that line have to be far enough apart to cross the whole panel.
     */
    private static final float DEPTH = 4000.0F;

    private PanelProjection() {
    }

    /**
     * The direction the panel's modules sit facing.
     *
     * <p>Dashpanels' three panel types only expose a horizontal {@code facing} property, so the
     * working face has to come from the block type: floor panels face up, wall panels face the way
     * they are mounted, ceiling panels face down. The check is by class name on purpose - a plain
     * name test fails soft (everything reads as a floor panel) instead of refusing to load when a
     * future Dashpanels renames the classes.
     */
    public static Direction normal(AbstractPanelBlockEntity panel) {
        BlockState state = panel.getBlockState();
        String type = state.getBlock().getClass().getSimpleName();
        if (type.contains("Ceiling")) {
            return Direction.DOWN;
        }
        if (type.contains("Wall") && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return Direction.UP;
    }

    /**
     * Block-local space of the panel, centred, with its working face turned toward the viewer and
     * tilted {@link #TILT_DEGREES} - mapped onto GUI pixels.
     *
     * <p>The y scale is negative because GUI pixels grow downward while the model grows upward;
     * back faces are drawn anyway, so the winding flip is harmless.
     */
    public static Matrix4f matrix(Direction normal, float centreX, float centreY, float scale) {
        Quaternionf align = new Quaternionf().rotateTo(
                new Vector3f(normal.getStepX(), normal.getStepY(), normal.getStepZ()),
                new Vector3f(0.0F, 1.0F, 0.0F));
        return new Matrix4f()
                .translate(centreX, centreY, 0.0F)
                .scale(scale, -scale, scale)
                .rotateX((float) Math.toRadians(-TILT_DEGREES))
                .rotate(align)
                .translate(-0.5F, -0.5F, -0.5F);
    }

    /** The module under {@code (mouseX, mouseY)}, or {@code null} when the ray hits nothing. */
    public static Module pick(AbstractPanelBlockEntity panel, Matrix4f camera,
                              double mouseX, double mouseY) {
        Matrix4f inverse = new Matrix4f(camera).invert();
        Vector3f near = inverse.transformPosition(new Vector3f((float) mouseX, (float) mouseY, -DEPTH));
        Vector3f far = inverse.transformPosition(new Vector3f((float) mouseX, (float) mouseY, DEPTH));
        Vec3 from = new Vec3(near.x, near.y, near.z);
        Vec3 to = new Vec3(far.x, far.y, far.z);

        Module best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, Module> entry : panel.getModules().asEntryList()) {
            VoxelShape shape = entry.getValue().getVoxelShape();
            if (shape == null || shape.isEmpty()) {
                continue;
            }
            BlockHitResult hit = shape.clip(from, to, BlockPos.ZERO);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            double distance = hit.getLocation().distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }
        return best;
    }
}
