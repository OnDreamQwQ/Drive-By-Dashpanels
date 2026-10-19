package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.ondreamqwq.dbd.net.TerminalClickPacket;
import java.util.LinkedHashMap;
import java.util.Map;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import moth.boxxed.panels.content.panel.ceiling.CeilingPanelBlockEntity;
import moth.boxxed.panels.content.panel.normal.PanelBlock;
import moth.boxxed.panels.content.panel.wall.WallPanelBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * The screen behind the panel remote: the panel itself, drawn through an orthographic camera that
 * looks at its working face from 45 degrees off the normal.
 *
 * <p>The pose chain is copied from Dashpanels' own panel renderer (and from the "panel viewer"
 * module in Dashpanels Extra, which is a working reference for drawing a panel inside a screen):
 * cancel the block's mounting, then replay {@code renderTransform} and
 * {@code getIndividualModuleTransform} for the modules. Hand rolling that transform is what made the
 * first two attempts show nothing, or one oversized face.
 *
 * <p>There is no way to read a module's current value through the public API, so this screen shows
 * the modules the way the panel shows them instead of inventing a control for each one. Clicking a
 * module sends its name to the server, which calls the module's own {@code onUse} - the same code
 * that runs when the player right clicks that module on the panel itself.
 */
public class PanelRemoteScreen extends Screen {

    private static final int BACKDROP = 0xFF0E1116;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFF9A9A9A;
    private static final int HIGHLIGHT = 0xFFFFC400;
    /** Camera tilt off the panel's working face, in degrees. */
    private static final float TILT = 45.0F;
    /** How much of the shorter screen axis the panel may take. */
    private static final float FILL = 0.78F;

    private final BlockPos panelPos;
    private final BlockPos receiverPos;

    private AbstractPanelBlockEntity panel;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    private PanelRemoteScreen(BlockPos panelPos, BlockPos receiverPos) {
        super(Component.translatable("gui.drive_by_dashpanels.remote.title"));
        this.panelPos = panelPos;
        this.receiverPos = receiverPos;
    }

    public static void open(BlockPos panelPos, BlockPos receiverPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PanelRemoteScreen open && open.panelPos.equals(panelPos)) {
            return;
        }
        minecraft.setScreen(new PanelRemoteScreen(panelPos, receiverPos));
    }

    @Override
    protected void init() {
        Level level = Minecraft.getInstance().level;
        BlockEntity blockEntity = level == null ? null : level.getBlockEntity(panelPos);
        panel = blockEntity instanceof AbstractPanelBlockEntity found ? found : null;

        int side = Math.round(Math.min(width, height) * FILL);
        panelWidth = side;
        panelHeight = side;
        panelLeft = (width - side) / 2;
        panelTop = (height - side) / 2;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately nothing: the world behind the screen is not part of the picture, and the
        // default implementation would blur and darken it for no reason.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);

        if (panel == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.drive_by_dashpanels.remote.unavailable"),
                    width / 2, height / 2 - 4, 0xFFCC5555);
            return;
        }

        Map<Module, int[]> rects = moduleRects();
        Module hovered = null;
        for (Map.Entry<Module, int[]> entry : rects.entrySet()) {
            if (inside(entry.getValue(), mouseX, mouseY)) {
                hovered = entry.getKey();
            }
        }

        drawPanel(graphics, partialTick);
        drawOverlay(graphics, rects, hovered);
    }

    private void drawPanel(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack pose = graphics.pose();

        RenderSystem.enableDepthTest();
        Lighting.setupForFlatItems();

        pose.pushPose();
        panelPose(pose);
        // The board itself. Dashpanels renders it as an ordinary block model, not as part of the
        // block entity renderer, so without this the screen shows modules floating in empty space.
        minecraft.getBlockRenderer().renderSingleBlock(
                panel.getBlockState(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                panel.getModelData(), null);

        pose.pushPose();
        pose.rotateAround(Axis.YP.rotationDegrees(moduleYaw()), 0.5F, 0.0F, 0.5F);
        panel.renderTransform(pose);
        for (Module module : panel.getModules().values()) {
            pose.pushPose();
            panel.getIndividualModuleTransform().accept(module, pose);
            pose.rotateAround(Axis.YP.rotationDegrees(180.0F),
                    (float) module.getSize().x / 32.0F, 0.0F, (float) module.getSize().y / 32.0F);
            module.render(panel, pose, partialTick, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();

        buffers.endBatch();
        pose.popPose();

        Lighting.setupFor3DItems();
        RenderSystem.disableDepthTest();
    }

    /**
     * Screen rectangles of every module, in the same transform the renderer uses, so the highlight
     * and the click test can never disagree with what is on screen.
     */
    private Map<Module, int[]> moduleRects() {
        Map<Module, int[]> out = new LinkedHashMap<>();
        PoseStack pose = new PoseStack();
        pose.pushPose();
        panelPose(pose);
        pose.pushPose();
        pose.rotateAround(Axis.YP.rotationDegrees(moduleYaw()), 0.5F, 0.0F, 0.5F);
        panel.renderTransform(pose);

        for (Module module : panel.getModules().values()) {
            pose.pushPose();
            panel.getIndividualModuleTransform().accept(module, pose);
            pose.rotateAround(Axis.YP.rotationDegrees(180.0F),
                    (float) module.getSize().x / 32.0F, 0.0F, (float) module.getSize().y / 32.0F);

            AABB box = module.getVoxelShape().bounds();
            Matrix4f matrix = new Matrix4f(pose.last().pose());
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                Vector4f point = new Vector4f(
                        (float) ((corner & 1) == 0 ? box.minX : box.maxX),
                        (float) ((corner & 2) == 0 ? box.minY : box.maxY),
                        (float) ((corner & 4) == 0 ? box.minZ : box.maxZ),
                        1.0F);
                point.mul(matrix);
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
            out.put(module, new int[]{
                    Math.round(minX), Math.round(minY),
                    Math.round(maxX - minX), Math.round(maxY - minY)});
            pose.popPose();
        }

        pose.popPose();
        pose.popPose();
        return out;
    }

    /**
     * The whole camera, ending in block space with the panel's face turned towards the viewer and
     * tilted by {@link #TILT}.
     */
    private void panelPose(PoseStack pose) {
        pose.translate(panelLeft, panelTop, 350.0F);
        pose.scale(panelWidth, panelHeight, -Math.max(panelWidth, panelHeight));
        pose.mulPose(Axis.XN.rotationDegrees(90.0F));

        Direction facing = facing();
        float blockYaw = Mth.wrapDegrees(facing.toYRot() + 180.0F);
        float pitch = panel instanceof WallPanelBlockEntity ? 90.0F
                : panel instanceof CeilingPanelBlockEntity ? 135.0F
                : 0.0F;
        Quaternionf mounted = new Quaternionf()
                .rotateY((float) Math.toRadians(blockYaw))
                .rotateX((float) Math.toRadians(pitch));

        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(mounted.invert());
        pose.mulPose(Axis.XP.rotationDegrees(TILT));
        pose.translate(-0.5F, -0.5F, -0.5F);
    }

    private Direction facing() {
        BlockState state = panel.getBlockState();
        return state.hasProperty(PanelBlock.FACING) ? state.getValue(PanelBlock.FACING) : Direction.NORTH;
    }

    private float moduleYaw() {
        Direction facing = facing();
        return facing.toYRot() + (facing.getAxis() == Direction.Axis.Z ? 0.0F : 180.0F);
    }

    private static boolean inside(int[] rect, double x, double y) {
        return x >= rect[0] && y >= rect[1] && x < rect[0] + rect[2] && y < rect[1] + rect[3];
    }

    private void drawOverlay(GuiGraphics graphics, Map<Module, int[]> rects, Module hovered) {
        if (hovered != null) {
            int[] rect = rects.get(hovered);
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], HIGHLIGHT);
            graphics.pose().popPose();
            graphics.drawCenteredString(font, hovered.getName(), width / 2, height - 32, NAME_COLOR);
        }
        graphics.drawCenteredString(font,
                Component.translatable("gui.drive_by_dashpanels.remote.hint"),
                width / 2, height - 16, HINT_COLOR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || panel == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        Module target = null;
        for (Map.Entry<Module, int[]> entry : moduleRects().entrySet()) {
            if (inside(entry.getValue(), mouseX, mouseY)) {
                target = entry.getKey();
            }
        }
        if (target == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        PacketDistributor.sendToServer(new TerminalClickPacket(receiverPos, panelPos, target.getName()));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
