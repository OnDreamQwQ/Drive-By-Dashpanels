package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.ondreamqwq.dbd.DbdConfig;
import io.github.ondreamqwq.dbd.content.PanelRemoteReceiverBlockEntity;
import io.github.ondreamqwq.dbd.net.TerminalClickPacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * The screen behind the panel remote: every panel the receiver serves, each one lifted out of the
 * world and drawn on its own, upright and facing the viewer.
 *
 * <p>The pose chain is the one Dashpanels Extra's panel viewer uses, which is known to render a panel
 * correctly inside a screen: cancel the panel's mounting, then replay {@code renderTransform} and
 * {@code getIndividualModuleTransform} for the modules. Mounting is cancelled by turning the panel's
 * own facing and type into a rotation and applying its inverse, so a panel placed facing the other
 * way ends up looking exactly like one facing this way - there is no second code path to get wrong.
 *
 * <p>Rotating is done by adding to those two angles instead of composing another rotation on top:
 * the panel then turns around its own centre, in the frame the rest of the chain already works in.
 *
 * <p>Clicking a module calls the module's own {@code onUse} <em>on the client</em> first, because for
 * several Dashpanels modules that method is where the client side interaction starts (a button starts
 * a hold, for instance) and the server side implementation is a plain pass. The click packet is still
 * sent, so modules that do act on the server also run there.
 */
public class PanelRemoteScreen extends Screen {

    private static final int BACKDROP = 0x8C0E1116;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB0B0B0;
    private static final int HIGHLIGHT = 0xFFFFC400;
    private static final float ZOOM_STEP = 0.12F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 3.0F;
    private static final float DRAG_SCALE = 0.55F;
    private static final float MAX_PITCH = 80.0F;
    private static final double CLICK_SLOP = 3.0D;

    private final BlockPos receiverPos;
    /** Per panel: {yaw, pitch} the player has dragged it by. */
    private final Map<BlockPos, float[]> views = new HashMap<>();

    private List<AbstractPanelBlockEntity> panels = List.of();
    private float zoom = 1.0F;
    private int cell;
    private int columns = 1;
    private int rows = 1;

    private boolean dragging;
    private boolean moved;
    private double pressX;
    private double pressY;
    private int dragIndex = -1;
    private Hit pressHit;

    private PanelRemoteScreen(BlockPos receiverPos) {
        super(Component.translatable("gui.drive_by_dashpanels.remote.title"));
        this.receiverPos = receiverPos;
    }

    public static void open(BlockPos receiverPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PanelRemoteScreen open && open.receiverPos.equals(receiverPos)) {
            return;
        }
        minecraft.setScreen(new PanelRemoteScreen(receiverPos));
    }

    @Override
    protected void init() {
        Level level = Minecraft.getInstance().level;
        BlockEntity blockEntity = level == null ? null : level.getBlockEntity(receiverPos);
        List<AbstractPanelBlockEntity> found = new ArrayList<>();
        if (blockEntity instanceof PanelRemoteReceiverBlockEntity receiver) {
            for (BlockPos pos : receiver.panels()) {
                if (level.getBlockEntity(pos) instanceof AbstractPanelBlockEntity panel) {
                    found.add(panel);
                }
            }
        }
        panels = List.copyOf(found);
        layout();
    }

    private void layout() {
        int count = Math.max(1, panels.size());
        columns = (int) Math.ceil(Math.sqrt(count));
        rows = (int) Math.ceil((double) count / (double) columns);
        float base = Math.min(width * 0.92F / columns, height * 0.86F / rows);
        cell = Math.max(24, Math.round(base * zoom));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing: the screen paints a translucent sheet over the world instead, so the player keeps
        // their bearings.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);

        if (panels.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.drive_by_dashpanels.remote.unavailable"),
                    width / 2, height / 2 - 4, 0xFFCC5555);
            return;
        }

        drawPanels(graphics, partialTick);
        drawOverlay(graphics, mouseX, mouseY);
    }

    private int left(int index) {
        return (width - cell * columns) / 2 + (index % columns) * cell;
    }

    private int top(int index) {
        return (height - cell * rows) / 2 + (index / columns) * cell;
    }

    private int panelAt(double mouseX, double mouseY) {
        for (int index = 0; index < panels.size(); index++) {
            int x = left(index);
            int y = top(index);
            if (mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell) {
                return index;
            }
        }
        return -1;
    }

    private void drawPanels(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack pose = graphics.pose();

        RenderSystem.enableDepthTest();
        Lighting.setupForFlatItems();

        for (int index = 0; index < panels.size(); index++) {
            AbstractPanelBlockEntity panel = panels.get(index);
            pose.pushPose();
            panelPose(pose, panel, left(index), top(index));
            // The board itself. Dashpanels renders it as an ordinary block model, not as part of the
            // block entity renderer, so without this the screen shows modules floating in nothing.
            minecraft.getBlockRenderer().renderSingleBlock(
                    panel.getBlockState(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    panel.getModelData(), null);

            pose.pushPose();
            pose.rotateAround(Axis.YP.rotationDegrees(moduleYaw(panel)), 0.5F, 0.0F, 0.5F);
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
        }

        Lighting.setupFor3DItems();
        RenderSystem.disableDepthTest();
    }

    /** The module under the cursor, and the rectangle it was found in. */
    private record Hit(AbstractPanelBlockEntity panel, Module module, int[] rect) {
    }

    private Hit hitAt(double mouseX, double mouseY) {
        for (int index = 0; index < panels.size(); index++) {
            AbstractPanelBlockEntity panel = panels.get(index);
            for (ModuleRect rect : moduleRects(panel, left(index), top(index))) {
                if (inside(rect.rect(), mouseX, mouseY)) {
                    return new Hit(panel, rect.module(), rect.rect());
                }
            }
        }
        return null;
    }

    private record ModuleRect(Module module, int[] rect) {
    }

    /**
     * Screen rectangles of a panel's modules, in the same transform the renderer uses, so the
     * highlight and the click test can never disagree with what is on screen.
     */
    private List<ModuleRect> moduleRects(AbstractPanelBlockEntity panel, int left, int top) {
        List<ModuleRect> out = new ArrayList<>();
        PoseStack pose = new PoseStack();
        pose.pushPose();
        panelPose(pose, panel, left, top);
        pose.pushPose();
        pose.rotateAround(Axis.YP.rotationDegrees(moduleYaw(panel)), 0.5F, 0.0F, 0.5F);
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
            out.add(new ModuleRect(module, new int[]{
                    Math.round(minX), Math.round(minY),
                    Math.round(maxX - minX), Math.round(maxY - minY)}));
            pose.popPose();
        }

        pose.popPose();
        pose.popPose();
        return out;
    }

    private float[] view(AbstractPanelBlockEntity panel) {
        return views.computeIfAbsent(panel.getBlockPos(), key -> new float[]{0.0F, DbdConfig.cameraTilt()});
    }

    /**
     * Camera for one panel cell: block space to screen, with the panel's face turned towards the
     * viewer and then turned by whatever the player has dragged it by.
     *
     * <p>The player's angles go into the mounting rotation rather than on top of it. Applied on top,
     * the rotation would happen in the world frame before the mounting is cancelled, and a panel
     * placed facing the other way would spin the other way - or show its edge. Inside the mounting
     * angles it is always the panel turning around its own centre.
     */
    private void panelPose(PoseStack pose, AbstractPanelBlockEntity panel, int left, int top) {
        float[] view = view(panel);

        pose.translate(left, top, 350.0F);
        pose.scale(cell, cell, -cell);
        pose.mulPose(Axis.XN.rotationDegrees(90.0F));

        Direction facing = facing(panel);
        float blockYaw = Mth.wrapDegrees(facing.toYRot() + 180.0F + view[0]);
        float pitch = panel instanceof WallPanelBlockEntity ? 90.0F
                : panel instanceof CeilingPanelBlockEntity ? 135.0F
                : 0.0F;
        pitch += view[1];
        Quaternionf mounted = new Quaternionf()
                .rotateY((float) Math.toRadians(blockYaw))
                .rotateX((float) Math.toRadians(pitch));

        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(mounted.invert());
        pose.translate(-0.5F, -0.5F, -0.5F);
    }

    private static Direction facing(AbstractPanelBlockEntity panel) {
        BlockState state = panel.getBlockState();
        return state.hasProperty(PanelBlock.FACING) ? state.getValue(PanelBlock.FACING) : Direction.NORTH;
    }

    private static float moduleYaw(AbstractPanelBlockEntity panel) {
        Direction facing = facing(panel);
        return facing.toYRot() + (facing.getAxis() == Direction.Axis.Z ? 0.0F : 180.0F);
    }

    private static boolean inside(int[] rect, double x, double y) {
        return x >= rect[0] && y >= rect[1] && x < rect[0] + rect[2] && y < rect[1] + rect[3];
    }

    private void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        Hit hit = dragging && moved ? null : hitAt(mouseX, mouseY);
        if (hit != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.renderOutline(hit.rect()[0], hit.rect()[1], hit.rect()[2], hit.rect()[3], HIGHLIGHT);
            graphics.pose().popPose();
            graphics.drawCenteredString(font, hit.module().getName(), width / 2, height - 32, NAME_COLOR);
        }
        graphics.drawCenteredString(font,
                Component.translatable("gui.drive_by_dashpanels.remote.hint"),
                width / 2, height - 16, HINT_COLOR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        dragging = true;
        moved = false;
        pressX = mouseX;
        pressY = mouseY;
        dragIndex = panelAt(mouseX, mouseY);
        pressHit = hitAt(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || button != 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        double dx = mouseX - pressX;
        double dy = mouseY - pressY;
        if (!moved) {
            if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) < CLICK_SLOP) {
                return true;
            }
            moved = true;
        }
        if (dragIndex >= 0 && dragIndex < panels.size()) {
            float[] view = view(panels.get(dragIndex));
            view[0] = Mth.wrapDegrees(view[0] + (float) dx * DRAG_SCALE);
            view[1] = Mth.clamp(view[1] + (float) dy * DRAG_SCALE, -MAX_PITCH, MAX_PITCH);
        }
        pressX = mouseX;
        pressY = mouseY;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            boolean wasDrag = moved;
            Hit target = wasDrag ? null : pressHit;
            dragging = false;
            moved = false;
            dragIndex = -1;
            pressHit = null;
            if (target != null) {
                activate(target);
                return true;
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Runs the module, the same way right clicking it on the panel does.
     *
     * <p>The client side call is the important one: Dashpanels' own modules often start their
     * interaction on the client and return a plain pass on the server, so a server-only call looks
     * like nothing happened at all.
     */
    private void activate(Hit hit) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            hit.module().onUse(minecraft.level, minecraft.player);
        }
        PacketDistributor.sendToServer(
                new TerminalClickPacket(receiverPos, hit.panel().getBlockPos(), hit.module().getName()));
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("gui.drive_by_dashpanels.remote.clicked", hit.module().getName()), true);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        zoom = Mth.clamp(zoom + (float) scrollY * ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
        layout();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
