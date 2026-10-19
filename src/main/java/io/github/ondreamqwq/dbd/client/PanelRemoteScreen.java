package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.ondreamqwq.dbd.DbdConfig;
import io.github.ondreamqwq.dbd.content.PanelRemoteReceiverBlockEntity;
import io.github.ondreamqwq.dbd.net.TerminalClickPacket;
import java.util.ArrayList;
import java.util.List;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.interaction.ModuleHoldInteraction;
import moth.boxxed.panels.api.module.interaction.ModuleHoldInteractionManager;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import moth.boxxed.panels.config.ClientConfig;
import moth.boxxed.panels.content.panel.ceiling.CeilingPanelBlockEntity;
import moth.boxxed.panels.content.panel.normal.PanelBlock;
import moth.boxxed.panels.content.panel.wall.WallPanelBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
import org.lwjgl.glfw.GLFW;

/**
 * The screen behind the panel remote: every panel the receiver is wired to, drawn together as the one
 * piece of hardware it is, at the place each panel actually occupies.
 *
 * <p>Panels are not laid out in a grid any more. Each one is drawn at its own block position inside a
 * single camera, so a wall of three panels shows up as a wall of three panels - the offsets between
 * them are the real ones. Panels of different kinds (wall vs floor vs ceiling) will still be placed
 * by their real positions, which is honest but rarely useful; an assembly is normally all one kind.
 *
 * <p>The pose chain is the one Dashpanels Extra's panel viewer uses: cancel the panel's mounting, then
 * replay {@code renderTransform} and {@code getIndividualModuleTransform} for the modules, and draw the
 * board itself with the ordinary block renderer.
 *
 * <p>Clicking a module calls the module's own {@code onUse} <em>on the client</em>, because for several
 * Dashpanels modules that is where the interaction starts: a button or a knob starts a hold there and
 * returns a plain pass on the server. If a hold started, the mouse belongs to that hold until it is let
 * go - dragging turns the knob instead of the view, and Escape ends the hold. That is the same
 * interaction you get in the world, just with the panel on screen instead of in front of you.
 */
public class PanelRemoteScreen extends Screen {

    private static final int BACKDROP = 0x8C0E1116;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB0B0B0;
    private static final int HOLD_COLOR = 0xFFFFC400;
    private static final int HIGHLIGHT = 0xFFFFC400;
    private static final float ZOOM_STEP = 0.12F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 3.0F;
    private static final float DRAG_SCALE = 0.55F;
    private static final float MAX_PITCH = 80.0F;
    private static final double CLICK_SLOP = 3.0D;
    /** Mouse travel handed to a hold interaction per scroll notch, in the same units a drag uses. */
    private static final double SCROLL_NOTCH = 40.0D;
    private static final float CAMERA_DEPTH = 350.0F;

    private final BlockPos receiverPos;
    /** Yaw and pitch the player has turned the whole assembly by. */
    private final float[] view = {0.0F, 45.0F};

    private List<AbstractPanelBlockEntity> panels = List.of();
    private float zoom = 1.0F;
    /** Pixels per block. */
    private int cell = 64;
    private double centreX;
    private double centreY;
    private double centreZ;

    private boolean dragging;
    private boolean moved;
    private double pressX;
    private double pressY;
    private Hit pressHit;

    /** Set while a module's own hold interaction owns the mouse. */
    private ModuleHoldInteraction<?> holding;
    private Hit holdHit;
    private int holdButton = -1;

    /** True when the hold was grabbed by the scroll wheel rather than by a held mouse button. */
    private boolean scrollMode;

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
        view[1] = DbdConfig.cameraTilt();
        layout();
    }

    /**
     * Pixels per block and the centre of the assembly, both derived from where the panels actually are.
     */
    private void layout() {
        if (panels.isEmpty()) {
            cell = 64;
            centreX = 0.5D;
            centreY = 0.5D;
            centreZ = 0.5D;
            return;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (AbstractPanelBlockEntity panel : panels) {
            BlockPos pos = panel.getBlockPos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        centreX = (minX + maxX) / 2.0D + 0.5D;
        centreY = (minY + maxY) / 2.0D + 0.5D;
        centreZ = (minZ + maxZ) / 2.0D + 0.5D;

        double span = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) + 1.0D;
        float base = (float) Math.min(width * 0.9D, height * 0.86D) / (float) span;
        cell = Math.max(16, Math.round(base * zoom));
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
        if (scrollMode) {
            // The wheel grabbed a module; moving off it lets go, the same way lifting the mouse would.
            Hit under = hitAt(mouseX, mouseY);
            if (under == null || holdHit == null || under.module() != holdHit.module()) {
                endHold();
            }
        }
        drawHold(graphics, partialTick);
        drawOverlay(graphics, mouseX, mouseY);
    }

    /**
     * One camera for the whole assembly, with its centre parked at the middle of the screen and every
     * panel offset from that centre by the distance it really has in the world.
     *
     * <p>The player's angles go into the mounting rotation rather than on top of it: applied on top
     * they would act in the world frame before the mounting is cancelled, and a panel facing the other
     * way would spin the other way or show its edge.
     */
    private void assemblyPose(PoseStack pose) {
        AbstractPanelBlockEntity reference = panels.get(0);
        pose.translate(width / 2.0F, height / 2.0F, CAMERA_DEPTH);
        pose.scale(cell, cell, -cell);
        pose.mulPose(Axis.XN.rotationDegrees(90.0F));
        pose.mulPose(mounted(reference).invert());
    }

    /** Where a panel sits relative to the middle of the assembly, in blocks. */
    private void panelOffset(PoseStack pose, BlockPos pos) {
        pose.translate((float) (pos.getX() - centreX), (float) (pos.getY() - centreY),
                (float) (pos.getZ() - centreZ));
    }

    private Quaternionf mounted(AbstractPanelBlockEntity panel) {
        Direction facing = facing(panel);
        float blockYaw = Mth.wrapDegrees(facing.toYRot() + 180.0F + view[0]);
        float pitch = panel instanceof WallPanelBlockEntity ? 90.0F
                : panel instanceof CeilingPanelBlockEntity ? 135.0F
                : 0.0F;
        pitch += view[1];
        return new Quaternionf()
                .rotateY((float) Math.toRadians(blockYaw))
                .rotateX((float) Math.toRadians(pitch));
    }

    private void drawPanels(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack pose = graphics.pose();

        RenderSystem.enableDepthTest();
        Lighting.setupForFlatItems();

        pose.pushPose();
        assemblyPose(pose);
        for (AbstractPanelBlockEntity panel : panels) {
            BlockPos pos = panel.getBlockPos();
            pose.pushPose();
            panelOffset(pose, pos);
            // The board itself. Dashpanels renders it as an ordinary block model, not through the block
            // entity renderer, so without this the screen shows modules floating in nothing.
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
            pose.popPose();
        }
        pose.popPose();

        // endBatch() with no argument only flushes the last render type used, so every type the models
        // can pick is drained explicitly.
        for (RenderType type : List.of(
                RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent(),
                RenderType.lines())) {
            buffers.endBatch(type);
        }

        Lighting.setupFor3DItems();
        RenderSystem.disableDepthTest();
    }

    /** The module under the cursor, and the rectangle it was found in. */
    private record Hit(AbstractPanelBlockEntity panel, Module module, int[] rect) {
    }

    private Hit hitAt(double mouseX, double mouseY) {
        for (AbstractPanelBlockEntity panel : panels) {
            for (ModuleRect rect : moduleRects(panel)) {
                if (inside(rect.rect(), mouseX, mouseY)) {
                    return new Hit(panel, rect.module(), rect.rect());
                }
            }
        }
        return null;
    }

    /** A hit's module measured against the current view, so the outline tracks zoom and rotation. */
    private int[] currentRect(Hit hit) {
        for (ModuleRect rect : moduleRects(hit.panel())) {
            if (rect.module() == hit.module()) {
                return rect.rect();
            }
        }
        return hit.rect();
    }

    private record ModuleRect(Module module, int[] rect) {
    }

    /**
     * Screen rectangles of a panel's modules, in the exact transform the renderer uses, so the
     * highlight and the click test can never disagree with what is on screen.
     */
    private List<ModuleRect> moduleRects(AbstractPanelBlockEntity panel) {
        List<ModuleRect> out = new ArrayList<>();
        PoseStack pose = new PoseStack();
        pose.pushPose();
        assemblyPose(pose);
        BlockPos pos = panel.getBlockPos();
        panelOffset(pose, pos);
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

    private void drawHold(GuiGraphics graphics, float partialTick) {
        ModuleHoldInteraction<?> active = activeHold();
        if (active == null) {
            return;
        }
        // The value indicator an interaction draws (dial sprite, number under the crosshair) lands in the
        // GUI batch, so the 3D panels are flushed out first and the indicator again afterwards: that way
        // it is always readable on top of them.
        graphics.flush();
        active.renderGui(graphics, partialTick);
        graphics.flush();
    }

    private void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        Hit hit = holding != null ? holdHit : (dragging && moved ? null : hitAt(mouseX, mouseY));
        if (hit != null) {
            // The rectangle is measured again every frame instead of reusing the one stored in the hit,
            // so the outline keeps hugging the module while the view is zoomed or turned.
            int[] rect = currentRect(hit);
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.renderOutline(rect[0], rect[1], rect[2], rect[3],
                    holding != null ? HOLD_COLOR : HIGHLIGHT);
            graphics.pose().popPose();
            graphics.drawCenteredString(font, hit.module().getName(), width / 2, height - 32, NAME_COLOR);
        }
        Component hint = holding != null
                ? Component.translatable("gui.drive_by_dashpanels.remote.holding")
                : Component.translatable("gui.drive_by_dashpanels.remote.hint");
        graphics.drawCenteredString(font, hint, width / 2, height - 16, HINT_COLOR);
    }

    @Override
    public void tick() {
        super.tick();
        ModuleHoldInteraction<?> active = activeHold();
        if (active != null) {
            active.tick();
        }
    }

    private static ModuleHoldInteraction<?> activeHold() {
        for (ModuleHoldInteraction<?> interaction : ModuleHoldInteractionManager.INTERACTIONS) {
            if (interaction.isActive()) {
                return interaction;
            }
        }
        return null;
    }

    private void endHold() {
        scrollMode = false;
        holdButton = -1;
        if (holding == null) {
            return;
        }
        holding.release();
        ModuleHoldInteractionManager.stop();
        holding = null;
        holdHit = null;
    }

    /** True when the player picked vanilla's click-once-to-grab mode instead of press-and-hold. */
    private static boolean toggleMode() {
        return ClientConfig.CLICK_FOR_MODULE_HOLD.get();
    }

    private static boolean isUseButton(int button) {
        return Minecraft.getInstance().options.keyUse.matchesMouse(button);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ModuleHoldInteraction<?> active = activeHold();
        if (active != null && holding != null) {
            // The module owns the mouse now, so it gets the raw button first - pick, attack and use are
            // all mapped inside the interaction, exactly as they are on a panel in the world.
            if (active.chooseInput(button, GLFW.GLFW_PRESS)) {
                return true;
            }
            if (button == holdButton) {
                if (toggleMode()) {
                    endHold();
                }
                return true;
            }
        }

        if (scrollMode) {
            endHold();
        }

        if (isUseButton(button)) {
            Hit hit = hitAt(mouseX, mouseY);
            if (hit != null) {
                activate(hit);
                holdButton = button;
            }
            return true;
        }

        if (button == 0) {
            dragging = true;
            moved = false;
            pressX = mouseX;
            pressY = mouseY;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (holding != null) {
            ModuleHoldInteraction<?> active = activeHold();
            if (active != null) {
                // Raw pixel deltas. MouseHandler hands the turn methods a y delta that grows downwards,
                // and the hold interactions were written against exactly that, so passing it straight
                // through keeps "drag up, value goes up" the way the real panel behaves.
                active.activeMouseMove(dragX, dragY);
            }
            return true;
        }
        if (!dragging || button != 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (!moved) {
            if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) < CLICK_SLOP) {
                return true;
            }
            moved = true;
        }
        // Reversed on purpose: dragging the view should push the panel the way the hand moves.
        view[0] = Mth.wrapDegrees(view[0] - (float) dragX * DRAG_SCALE);
        view[1] = Mth.clamp(view[1] - (float) dragY * DRAG_SCALE, -MAX_PITCH, MAX_PITCH);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (holding != null && button == holdButton && !toggleMode()) {
            // Press to grab, let go to drop - what a real panel does with a momentary switch.
            endHold();
            return true;
        }
        if (holding != null) {
            ModuleHoldInteraction<?> active = activeHold();
            if (active != null && active.chooseInput(button, GLFW.GLFW_RELEASE)) {
                return true;
            }
        }
        if (button == 0) {
            dragging = false;
            moved = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (holding != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                endHold();
                return true;
            }
            ModuleHoldInteraction<?> active = activeHold();
            if (active != null && active.keyPress(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (holding != null) {
            ModuleHoldInteraction<?> active = activeHold();
            if (active != null && active.keyRelease(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        endHold();
        super.onClose();
    }

    @Override
    public void removed() {
        endHold();
        super.removed();
    }

    /**
     * Runs the module, the same way right clicking it on the panel does.
     *
     * <p>The client side call is the important one. If it starts a hold, the hold owns the mouse from
     * here on and syncs the value to the server itself, so no click packet is sent - sending one would
     * apply the click a second time on the server.
     */
    private void activate(Hit hit) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            hit.module().onUse(minecraft.level, minecraft.player);
        }
        ModuleHoldInteraction<?> active = activeHold();
        if (active != null) {
            holding = active;
            holdHit = hit;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return;
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

        // Ctrl+Alt+wheel zooms; a bare wheel turns the knob it is pointing at.
        if (hasControlDown() && hasAltDown()) {
            zoom = Mth.clamp(zoom + (float) scrollY * ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
            layout();
            return true;
        }

        Hit hit = hitAt(mouseX, mouseY);
        if (hit == null) {
            return true;
        }
        if (scrollMode && holdHit != null && holdHit.module() == hit.module()) {
            feedScroll(scrollY);
            return true;
        }
        endHold();
        // Start the module the same way a press would, then drive it with the wheel instead of the mouse.
        activate(hit);
        if (holding == null) {
            return true;
        }
        scrollMode = true;
        feedScroll(scrollY);
        return true;
    }

    /** Hands one notch of wheel travel to the interaction the way a drag of the same length would. */
    private void feedScroll(double scrollY) {
        ModuleHoldInteraction<?> active = activeHold();
        if (active != null) {
            active.activeMouseMove(SCROLL_NOTCH * scrollY, 0.0D);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
