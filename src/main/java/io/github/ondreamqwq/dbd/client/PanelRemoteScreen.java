package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.ondreamqwq.dbd.net.TerminalClickPacket;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * The screen behind the panel remote: the panel itself, drawn by its own block entity renderer
 * through an orthographic camera tilted off its working face.
 *
 * <p>There is no way to read a module's current value through the public API, so this screen shows
 * the modules the way the panel shows them instead of inventing a control for each one. Clicking a
 * module sends its name to the server, which calls the module's own {@code onUse} - the same code
 * that runs when the player right clicks that module on the panel itself.
 */
public class PanelRemoteScreen extends Screen {

    private static final int BACKDROP = 0xFF0E1116;
    private static final float SCALE_FRACTION = 0.62F;

    private final BlockPos panelPos;
    private final BlockPos receiverPos;

    private AbstractPanelBlockEntity panel;
    private Matrix4f camera;

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
        camera = panel == null ? null : PanelProjection.matrix(
                PanelProjection.normal(panel),
                width / 2.0F,
                height / 2.0F,
                Math.min(width, height) * SCALE_FRACTION);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately nothing: the world behind the screen is not part of the picture, and the
        // default implementation would blur and darken it for no reason.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);

        if (panel == null || camera == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.drive_by_dashpanels.remote.unavailable"),
                    width / 2, height / 2 - 4, 0xFFCC5555);
            return;
        }

        Module hovered = PanelProjection.pick(panel, camera, mouseX, mouseY);
        drawPanel(graphics, partialTick, hovered);
        drawOverlay(graphics, hovered);
    }

    private void drawPanel(GuiGraphics graphics, float partialTick, Module hovered) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        BlockEntityRenderer<AbstractPanelBlockEntity> renderer =
                minecraft.getBlockEntityRenderDispatcher().getRenderer(panel);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.mulPose(camera);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        if (renderer != null) {
            renderer.render(panel, partialTick, pose, buffer,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
        buffer.endBatch();

        if (hovered != null) {
            hovered.renderOutline(pose, buffer, partialTick, 0xFFFFC400);
            buffer.endBatch();
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableDepthTest();
        pose.popPose();
    }

    private void drawOverlay(GuiGraphics graphics, Module hovered) {
        if (hovered != null) {
            graphics.drawCenteredString(font, hovered.getName(), width / 2, height - 32, 0xFFFFFFFF);
        }
        graphics.drawCenteredString(font,
                Component.translatable("gui.drive_by_dashpanels.remote.hint"),
                width / 2, height - 16, 0xFF9A9A9A);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || panel == null || camera == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        Module module = PanelProjection.pick(panel, camera, mouseX, mouseY);
        if (module == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        PacketDistributor.sendToServer(new TerminalClickPacket(receiverPos, panelPos, module.getName()));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
