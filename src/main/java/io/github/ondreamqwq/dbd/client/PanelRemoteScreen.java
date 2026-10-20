package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import io.github.ondreamqwq.dbd.DbdConfig;
import io.github.ondreamqwq.dbd.content.PanelRemoteReceiverBlockEntity;
import io.github.ondreamqwq.dbd.net.TerminalClickPacket;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.interaction.ModuleHoldInteraction;
import moth.boxxed.panels.api.module.interaction.ModuleHoldInteractionManager;
import moth.boxxed.panels.api.module.io.IInput;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import moth.boxxed.panels.content.panel.ceiling.CeilingPanelBlockEntity;
import moth.boxxed.panels.content.panel.normal.PanelBlock;
import moth.boxxed.panels.content.panel.wall.WallPanelBlockEntity;
import moth.boxxed.panels.network.packet.DefaultModuleUpdatePacket;
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
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

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
    /**
     * The crosshair is drawn from its texture rather than through the GUI sprite manager.
     *
     * <p>{@code GuiGraphics.blitSprite} looks the sprite up in {@code GuiSpriteManager}, and from
     * {@code ScreenEvent.Render.Post} that lookup produced nothing here while plain fills and text in the very
     * same callback drew fine - so the sprite is what fails, not the layer. Blitting the PNG directly goes
     * through {@code RenderType.guiTextured(texture)} instead, which is a path we know works from this layer.
     * Resource packs still apply: the path is resolved by the texture manager, not baked into the jar.
     */
    private static final ResourceLocation CROSSHAIR_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/crosshair.png");
    private boolean pinned;
    private boolean pinSkipDelta;
    private double pinX;
    private double pinY;
    private double lastRawX;
    private double lastRawY;

    private static Field STARTED;
    private static boolean STARTED_RESOLVED;

    private static final int CROSSHAIR_SIZE = 15;
    private static final int INFO_X = 6;
    private static final int INFO_Y = 6;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB0B0B0;
    private static final int HOLD_COLOR = 0xFFFFC400;
    private static final int HIGHLIGHT = 0xFFFFC400;
    private static final int INFO_BACKDROP = 0xD8101014;
    private static final int INFO_EDGE = 0xFF585858;
    private static final int VALUE_COLOR = 0xFF7FE0A0;
    private static final float ZOOM_STEP = 0.12F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 3.0F;
    private static final float DRAG_SCALE = 0.55F;
    private static final double CLICK_SLOP = 3.0D;
    /** Mouse travel handed to a hold interaction per scroll notch, in the same units a drag uses. */
    private static final double SCROLL_NOTCH = 40.0D;
    /** How far the camera sits from the assembly when projecting in perspective, in GUI pixels. */
    private static final float PERSPECTIVE_DEPTH = 1500.0F;

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
        if (holding != null) {
            drivePinnedPointer();
        } else {
            pointerOnScreen();
        }
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
        // Layer order, bottom to top: backdrop, models, crosshair + its indicator, then our own text.
        // The crosshair, the value indicator and the frames are drawn from ScreenEvent.Render.Post instead,
        // because anything queued here can still be overtaken by a batch the game flushes later.
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
        if (DbdConfig.perspective()) {
            // The base of the GUI pose already sits far back (the game pushes the whole 2D layer down the
            // z axis), and a picking pose starts at zero. Subtracting the base keeps the assembly at the
            // same distance in camera space either way, which perspective needs and orthographic did not.
            float baseZ = pose.last().pose().m32();
            pose.translate(width / 2.0F, height / 2.0F, -PERSPECTIVE_DEPTH - baseZ);
        } else {
            pose.translate(width / 2.0F, height / 2.0F, CAMERA_DEPTH);
        }
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

    /**
     * The perspective matrix, or {@code null} while the remote stays orthographic.
     *
     * <p>The field of view is derived from the canvas and the camera distance, so a block stays the same
     * number of pixels across either way and flipping the switch never resizes the picture.
     */
    private Matrix4f perspectiveProjection() {
        if (!DbdConfig.perspective()) {
            return null;
        }
        float fov = (float) (2.0D * Math.atan(height / (2.0D * PERSPECTIVE_DEPTH)));
        return new Matrix4f().perspective(fov, (float) width / (float) height, 3.0F, PERSPECTIVE_DEPTH * 8.0F);
    }

    private void drawPanels(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        // Our own buffer source instead of the shared one. A BufferSource we created is empty to begin with,
        // and getBuffer() ends the previous shared type whenever the type changes - so after the models are
        // drawn there is at most one pending builder, and a single endBatch() submits it (immediate sources
        // have no fixed buffers). Nothing can survive into the end of the frame, where it used to be
        // submitted after the overlay and paint the panels on top of the crosshair.
        MultiBufferSource.BufferSource buffers = ownBuffers();
        PoseStack pose = graphics.pose();

        Matrix4f projected = perspectiveProjection();
        Matrix4f savedProjection = null;
        VertexSorting savedSorting = null;
        if (projected != null) {
            savedProjection = RenderSystem.getProjectionMatrix();
            savedSorting = RenderSystem.getVertexSorting();
            RenderSystem.setProjectionMatrix(projected, VertexSorting.DISTANCE_TO_ORIGIN);
        }

        RenderSystem.enableDepthTest();
        // The GUI pass runs with depth writes off, so nothing the panels draw would ever be recorded in the
        // depth buffer and a module drawn later simply painted over a board drawn earlier - the assembly
        // looked like the modules were floating in front of it. Writes are turned on here, for the panels
        // only, and the buffer is cleared first so the world behind the screen does not interfere.
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        Lighting.setupForFlatItems();

        try {
            pose.pushPose();
            assemblyPose(pose);

            // Boards first, so every surface is in the depth buffer before a single module is drawn.
            for (AbstractPanelBlockEntity panel : panels) {
                drawBoard(pose, panel, buffers);
            }
            // Then every module of every panel.
            for (AbstractPanelBlockEntity panel : panels) {
                drawModules(pose, panel, buffers, partialTick);
            }
            // And the boards once more on top. A module renderer may pick a render type that ignores depth
            // entirely - several do - and then nothing stops a module of a far panel from painting over a
            // near board. Replaying the boards with depth testing on puts the near surface back in front;
            // modules that stand off their own board survive, because they really are closer.
            for (AbstractPanelBlockEntity panel : panels) {
                drawBoard(pose, panel, buffers);
            }

            pose.popPose();
        } finally {
            // Submit now, while the panels are still the thing being drawn.
            buffers.endBatch();
        }

        drainBuffers(Minecraft.getInstance().renderBuffers().bufferSource());

        Lighting.setupFor3DItems();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        if (savedProjection != null) {
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
    }

    private ByteBufferBuilder ownPool;

    /**
     * Draws one board at its real offset.
     *
     * <p>Dashpanels renders the board as an ordinary block model rather than through the block entity
     * renderer, so the screen has to draw it or the modules end up floating in nothing.
     */
    private void drawBoard(PoseStack pose, AbstractPanelBlockEntity panel, MultiBufferSource buffers) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        pose.pushPose();
        panelOffset(pose, panel.getBlockPos());
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                panel.getBlockState(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                panel.getModelData(), null);
        pose.popPose();
    }

    /** Draws one panel's modules, replaying the transforms Dashpanels itself uses for them. */
    private void drawModules(PoseStack pose, AbstractPanelBlockEntity panel, MultiBufferSource buffers,
                             float partialTick) {
        pose.pushPose();
        panelOffset(pose, panel.getBlockPos());
        pose.pushPose();
        pose.rotateAround(Axis.YP.rotationDegrees(moduleYaw(panel)), 0.5F, 0.0F, 0.5F);
        panel.renderTransform(pose);
        for (Module module : panel.getModules().values()) {
            pose.pushPose();
            panel.getIndividualModuleTransform().accept(module, pose);
            pose.rotateAround(Axis.YP.rotationDegrees(180.0F),
                    (float) module.getSize().x / 32.0F, 0.0F, (float) module.getSize().y / 32.0F);
            // Some module renderers switch depth state for their own sprites and do not put it back, so the
            // state is asserted again for every element instead of once per frame.
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            module.render(panel, pose, partialTick, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        pose.popPose();
    }

    private MultiBufferSource.BufferSource ownBuffers() {
        if (ownPool == null) {
            ownPool = new ByteBufferBuilder(1 << 20);
        } else {
            ownPool.clear();
        }
        return MultiBufferSource.immediate(ownPool);
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
            Matrix4f projection = perspectiveProjection();
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
                if (projection != null) {
                    point.mul(projection);
                    if (point.w != 0.0F) {
                        point.div(point.w);
                    }
                    point.x = (point.x * 0.5F + 0.5F) * width;
                    point.y = (0.5F - point.y * 0.5F) * height;
                }
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

    /**
     * The value indicator an interaction draws (a dial sprite, a number under the crosshair). It shares the
     * crosshair's layer - above the models, below our own text - so the depth test is off while it is drawn
     * and the batch is flushed before it is turned back on.
     */
    private void drawHold(GuiGraphics graphics, float partialTick) {
        ModuleHoldInteraction<?> active = activeHold();
        if (active == null) {
            return;
        }
        beginTopPass(graphics);
        active.renderGui(graphics, partialTick);
        endTopPass(graphics);
    }

    private void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        Hit hit = holding != null ? holdHit : (dragging && moved ? null : hitAt(mouseX, mouseY));
        // The panels are real 3D geometry with depth written, and a GuiGraphics draw only reaches the screen
        // when its buffer is flushed - which happens at the end of the frame unless asked otherwise. So the
        // overlay is flushed here, by hand, while the depth test is off; re-enabling it before the flush was
        // what kept the name box hidden behind the model.
        beginTopPass(graphics);
        if (hit != null) {
            // The rectangle is measured again every frame instead of reusing the one stored in the hit,
            // so the outline keeps hugging the module while the view is zoomed or turned.
            int[] rect = currentRect(hit);
            Module module = liveModule(hit);
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.renderOutline(rect[0], rect[1], rect[2], rect[3],
                    holding != null ? HOLD_COLOR : HIGHLIGHT);
            graphics.pose().popPose();
            if (DbdConfig.infoBox()) {
                drawInfoBox(graphics, module);
            }
        }
        Component hint = holding != null
                ? Component.translatable("gui.drive_by_dashpanels.remote.holding")
                : Component.translatable("gui.drive_by_dashpanels.remote.hint");
        graphics.drawCenteredString(font, hint, width / 2, height - 16, HINT_COLOR);
        endTopPass(graphics);
    }

    /**
     * The module object the panel holds right now. Every block entity update rebuilds the module map from
     * the synced tag, so a hit stored when the click happened points at an object that no longer carries
     * the current value; looking the module up by name gives the one the renderer also draws.
     */
    private static Module liveModule(Hit hit) {
        Module fresh = hit.panel().getModule(hit.module().getName());
        return fresh != null ? fresh : hit.module();
    }

    /**
     * Starts a pass that has to land on top of the 3D panels.
     *
     * <p>Turning the depth test off by hand is not enough: every RenderType re-applies its own depth state
     * when its batch is drawn, so a type that asks for LEQUAL quietly turns the test back on. That is why the
     * dial (a GUI type that never depth tests) showed while its number (text) was swallowed by the model.
     * Clearing the depth buffer the panels filled in makes any depth test pass instead.
     */
    private void beginTopPass(GuiGraphics graphics) {
        flushEveryBatch(graphics);
        // glClear honours the depth write mask: with the mask off - which is where the GUI batch leaves it -
        // the clear is a silent no-op and the panels keep their depth.
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
    }

    /**
     * Drains every render type that still holds vertices, not just the last one.
     *
     * <p>{@code GuiGraphics.flush()} ends only the most recent shared type (plus a handful of fixed ones).
     * A module is free to draw through any type it likes - text, an item model, its own sprite batch - and
     * those vertices then sit in the buffer until the game flushes the GUI at the very end of the frame,
     * which is <em>after</em> this screen has drawn its overlay. That is why the frame and the crosshair
     * kept ending up underneath the panels while every depth trick in the book changed nothing: it was never
     * a depth problem in the end, it was the model being submitted later.
     *
     * <p>Juggling the depth buffer is still worth keeping for the types that do depth test, but the ordering
     * is what actually decides the picture.
     */
    private static void flushEveryBatch(GuiGraphics graphics) {
        graphics.flush();
        drainBuffers(Minecraft.getInstance().renderBuffers().bufferSource());
    }

    /**
     * Submits every vertex still sitting in the shared buffer source, whatever type queued it.
     *
     * <p>The no-argument {@code endBatch()} is <em>not</em> a drain: it ends {@code lastSharedType} plus the
     * fixed buffers, and {@code endLastBatch()} does nothing once {@code lastSharedType} is null - which it is
     * after any earlier {@code GuiGraphics.flush()}. Calling it in a loop therefore spins while the builders
     * stay queued, they get submitted at the end of the frame, and the panels end up painted over the overlay.
     *
     * <p>{@code endBatch(type)} is the one that actually removes a builder and draws it. Only one shared type
     * can be pending at a time - {@code getBuffer} ends the previous one when the type changes - so ending the
     * pending types one at a time cannot corrupt the shared buffer.
     */
    private static void drainBuffers(MultiBufferSource.BufferSource buffers) {
        for (int i = 0; i < 64; i++) {
            Map<?, ?> started = startedBuilders(buffers);
            if (started == null) {
                // Reflection failed; the panels still get drawn, just later than we would like.
                buffers.endBatch();
                return;
            }
            if (started.isEmpty()) {
                // Nothing shared is queued - but the block sheets are fixed buffers of this source, they are
                // not in startedBuilders at all, and only the no-argument endBatch walks them. Dashpanels'
                // PreLoadedModel writes straight into renderBuffers().bufferSource() and ignores the source it
                // is handed, so every module of every module lands in exactly those fixed buffers. They used
                // to be submitted at the end of the frame, after our boards and with depth testing already
                // switched off - which is why every module painted over the panel it is mounted on.
                for (int n = 0; n < 4; n++) {
                    buffers.endBatch();
                }
                return;
            }
            Object next = started.keySet().iterator().next();
            if (!(next instanceof RenderType type)) {
                return;
            }
            buffers.endBatch(type);
        }
    }

    private static Map<?, ?> startedBuilders(MultiBufferSource.BufferSource buffers) {
        if (!STARTED_RESOLVED) {
            STARTED_RESOLVED = true;
            try {
                STARTED = MultiBufferSource.BufferSource.class.getDeclaredField("startedBuilders");
                STARTED.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                STARTED = null;
            }
        }
        if (STARTED == null) {
            return null;
        }
        try {
            return (Map<?, ?>) STARTED.get(buffers);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private void endTopPass(GuiGraphics graphics) {
        flushEveryBatch(graphics);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
    /**
     * The crosshair, drawn last so it sits above the panels instead of behind them. A module that asked for
     * the crosshair to be hidden (a joystick being dragged) hides this one too, exactly like the real one.
     */
    /**
     * The layer that has to sit above everything else.
     *
     * <p>Called from {@code ScreenEvent.Render.Post}, i.e. after this screen has finished drawing and after
     * the game has had every chance to flush the model batches. Drawing the frames and the crosshair from
     * inside {@code render} was what kept them underneath the panels: a module may queue geometry through a
     * render type of its own, and that batch is only submitted at the end of the frame - after our overlay.
     */
    public void renderTopLayer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawCrosshair(graphics);
        drawHold(graphics, partialTick);
        drawOverlay(graphics, mouseX, mouseY);
    }

    private void drawCrosshair(GuiGraphics graphics) {
        if (holding != null && holding.getGuiContext() != null && !holding.getGuiContext().crosshairVisibility) {
            return;
        }
        // Vanilla draws it at these exact coordinates, taken from the graphics rather than from the screen:
        // Screen.width/height are only valid once init() ran, and this layer runs without that guarantee.
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int x = (guiWidth - CROSSHAIR_SIZE) / 2;
        int y = (guiHeight - CROSSHAIR_SIZE) / 2;
        beginTopPass(graphics);
        graphics.blit(CROSSHAIR_TEXTURE, x, y, 0.0F, 0.0F, CROSSHAIR_SIZE, CROSSHAIR_SIZE, CROSSHAIR_SIZE,
                CROSSHAIR_SIZE);
        endTopPass(graphics);
    }
    /**
     * Name, a live reading and how the module is being driven, drawn by us right above the module.
     * Dashpanels' own tooltip was tried first, but it is pinned to the screen centre and fades through a
     * static opacity field that vanilla's HUD pass keeps pulling back to zero, so it never showed up here.
     */
    private void drawInfoBox(GuiGraphics graphics, Module module) {
        String name = module.getName();
        String reading = readValue(module);
        String state = holding == null ? null
                : Component.translatable("gui.drive_by_dashpanels.remote.state").getString();
        // Dashpanels has no cursor-shape hook: the only pointer state a hold exposes is whether it wants
        // the crosshair hidden. Mirror that flag in the box so the module's own hint is not lost on a screen.
        if (state != null && holding.getGuiContext() != null && !holding.getGuiContext().crosshairVisibility) {
            state = state + "  " + Component.translatable("gui.drive_by_dashpanels.remote.pointer").getString();
        }
        int widest = Math.max(font.width(name), reading == null ? 0 : font.width(reading));
        if (state != null) {
            widest = Math.max(widest, font.width(state));
        }
        int boxWidth = widest + 8;
        int boxHeight = 13 + (reading == null ? 0 : 9) + (state == null ? 0 : 9);
        // Pinned in the top left corner instead of following the module: the panel can be turned and zoomed,
        // and a box that chased it ended up on top of the very module it describes (and behind the model when
        // the depth flush slipped).
        int boxX = INFO_X;
        int boxY = INFO_Y;
        graphics.flush();
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, INFO_BACKDROP);
        graphics.renderOutline(boxX, boxY, boxWidth, boxHeight, INFO_EDGE);
        graphics.drawString(font, name, boxX + 4, boxY + 3, holding != null ? HOLD_COLOR : NAME_COLOR, false);
        int line = boxY + 12;
        if (reading != null) {
            graphics.drawString(font, reading, boxX + 4, line, VALUE_COLOR, false);
            line += 9;
        }
        if (state != null) {
            graphics.drawString(font, state, boxX + 4, line, HINT_COLOR, false);
        }
    }

    /**
     * The reading of a module, taken from the module's own saved tag the way the panel writes it to disk -
     * every module that carries a value implements {@code saveData}, so this covers modules from other
     * mods too. Falls back to the analog input value for modules that keep their state elsewhere.
     */
    private static String readValue(Module module) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            CompoundTag tag = new CompoundTag();
            try {
                module.saveData(tag, minecraft.level.registryAccess());
            } catch (RuntimeException ignored) {
                return analog(module);
            }
            StringBuilder builder = new StringBuilder();
            int shown = 0;
            for (String key : tag.getAllKeys()) {
                if (shown >= 3) {
                    break;
                }
                String text = describe(key, tag.get(key));
                if (text == null) {
                    continue;
                }
                if (shown > 0) {
                    builder.append("  ");
                }
                builder.append(text);
                shown++;
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }
        return analog(module);
    }

    private static String describe(String key, Tag value) {
        if (value instanceof FloatTag number) {
            return key + " " + String.format(java.util.Locale.ROOT, "%.2f", number.getAsDouble());
        }
        if (value instanceof DoubleTag number) {
            return key + " " + String.format(java.util.Locale.ROOT, "%.2f", number.getAsDouble());
        }
        if (value instanceof ByteTag flag) {
            return key + (flag.getAsByte() == 0 ? ' ' + "off" : ' ' + "on");
        }
        if (value instanceof NumericTag number) {
            return key + " " + number.getAsInt();
        }
        return null;
    }

    /** Named getters worth trying when the saved tag does not describe the value on its own. */
    private static final String[] VALUE_GETTERS = {"getAngle", "getPosition", "getValue", "getTurn", "isPressed", "isToggled"};

    private static final Map<Class<?>, List<Method>> GETTERS = new HashMap<>();

    private static String analog(Module module) {
        List<Method> getters = GETTERS.computeIfAbsent(module.getClass(), type -> {
            List<Method> found = new ArrayList<>();
            for (String name : VALUE_GETTERS) {
                try {
                    found.add(type.getMethod(name));
                } catch (NoSuchMethodException ignored) {
                    // that module simply does not expose this one
                }
            }
            return found;
        });
        for (Method getter : getters) {
            try {
                Object value = getter.invoke(module);
                if (value instanceof Float number) {
                    return String.format(java.util.Locale.ROOT, "%.2f", number);
                }
                if (value != null) {
                    return value.toString();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through to the next getter
            }
        }
        if (module instanceof IInput input) {
            return Integer.toString(input.getAnalog());
        }
        return null;
    }

    @Override
    public void tick() {
        super.tick();
        // Safety net: if the release never reaches the screen (focus loss, a screen switch, our own
        // consumption order) drop the module as soon as the physical button is up again, instead of
        // leaving it held down until something else happens to clear it.
        if (holding != null && holdButton >= 0
                && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), holdButton)
                   == GLFW.GLFW_RELEASE) {
            endHold();
        }
        ModuleHoldInteraction<?> active = activeHold();
        if (active != null) {
            active.tick();
        }
        enforceCursor();
    }

    /**
     * Re-asserts the pointer state.
     *
     * <p>In a large pack the cursor mode is shared, and other mods write to it on their own schedule - the
     * tested instance hides the pointer fine, while the big pack shows it again while a module is held.
     * Doing this from the render tail, from the HUD tail and once per tick means whichever of them runs
     * last in a given frame still ends up with the state this screen wants.
     */
    public void enforceCursor() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != this) {
            return;
        }
        long handle = minecraft.getWindow().getWindow();
        if (holding != null) {
            hideCursor(handle);
            return;
        }
        if (pinned) {
            unpinPointer();
        }
        if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL) {
            minecraft.mouseHandler.releaseMouse();
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
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
        unpinPointer();
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
                // Never latch: letting go of the button is what drops the module.
                endHold();
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
            if (pinned) {
                // The pinned pointer feeds the interaction from its own poll, once per frame; letting the
                // absolute-position delta through as well would double every movement.
                return true;
            }
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
        // No pitch limit: a ceiling panel sits above the module plane, so its modules only come into view
        // once the view is turned well past the horizon and over the top.
        view[1] -= (float) dragY * DRAG_SCALE;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (holding != null && button == holdButton) {
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
        restorePointer();
        super.removed();
    }

    /**
     * Makes sure the system pointer is drawn while this screen is up.
     *
     * <p>Vanilla releases the mouse once, when the screen opens, and after that every mod is on its own.
     * In a large pack some other mod puts the cursor back to {@code GLFW_CURSOR_DISABLED} every tick, and
     * then this screen is usable - clicks land where the invisible pointer is - but you cannot see what
     * you are aiming at. Checking once per frame costs a GLFW state query and only writes when the state
     * actually drifted.
     */
    private void pointerOnScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        long handle = minecraft.getWindow().getWindow();
        if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL) {
            minecraft.mouseHandler.releaseMouse();
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    /**
     * Hands the pointer back to the game on the way out.
     *
     * <p>Without this the cursor stays free after closing, and the camera stops turning: the player is
     * left in the world with a pointer they cannot get rid of until they open another screen.
     */
    private void restorePointer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.level == null) {
            return;
        }
        minecraft.mouseHandler.grabMouse();
    }
    /** Hides the pointer: the mode is only half of it, the transparent cursor image is what sticks. */
    private static void hideCursor(long handle) {
        if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_HIDDEN) {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }
        long cursor = invisibleCursor();
        if (cursor != 0L) {
            GLFW.glfwSetCursor(handle, cursor);
        }
    }
    private static long invisibleCursor;
    private static boolean invisibleCursorTried;

    /**
     * A 1x1 fully transparent cursor image.
     *
     * <p>Hiding the pointer through the input mode alone is a losing game in a big pack: the mode is one
     * global value and other mods write to it on their own schedule - which is why the same code hid the
     * pointer in a small instance and not in the large one. A custom cursor image survives that: the arrow
     * is replaced by transparent pixels, so the pointer stays invisible whichever mode somebody else picks.
     */
    private static long invisibleCursor() {
        if (invisibleCursorTried) {
            return invisibleCursor;
        }
        invisibleCursorTried = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pixels = stack.malloc(4);
            GLFWImage image = GLFWImage.malloc(stack);
            image.set(1, 1, pixels);
            invisibleCursor = GLFW.glfwCreateCursor(image, 0, 0);
        } catch (Throwable ignored) {
            invisibleCursor = 0L;
        }
        return invisibleCursor;
    }
    /**
     * Locks the pointer onto the module and hides it, the way turning the camera feels: the player drags
     * and the value follows, no free cursor to chase and nothing to aim at a second time.
     */
    private void pinPointer(Hit hit) {
        Minecraft minecraft = Minecraft.getInstance();
        long handle = minecraft.getWindow().getWindow();
        int[] rect = currentRect(hit);
        pinX = rect[0] + rect[2] / 2.0D;
        pinY = rect[1] + rect[3] / 2.0D;

        MemoryStack stack = MemoryStack.stackPush();
        try {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(handle, x, y);
            lastRawX = x.get(0);
            lastRawY = y.get(0);
        } finally {
            stack.pop();
        }

        movePointerToPin(handle, minecraft);
        hideCursor(handle);
        pinned = true;
        pinSkipDelta = true;
    }

    /** Gives the pointer back when the module is dropped. */
    private void unpinPointer() {
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().getWindow(), 0L);
        if (!pinned) {
            return;
        }
        pinned = false;
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_CURSOR,
                GLFW.GLFW_CURSOR_NORMAL);
    }

    private void movePointerToPin(long handle, Minecraft minecraft) {
        double scale = minecraft.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(handle, pinX * scale, pinY * scale);
    }

    /**
     * Reads how far the pointer drifted since the last frame, recentres it on the module, and feeds that
     * movement to the interaction.
     *
     * <p>Recentring every frame is what keeps the drag unbounded: otherwise the OS cursor walks off the
     * module (and eventually off the window) and the movement stops. The deltas are polled here rather than
     * taken from {@code mouseDragged}, because a recentred pointer makes the absolute-position delta the
     * screen hands us meaningless - it would report the snap back instead of the drag.
     */
    private void drivePinnedPointer() {
        ModuleHoldInteraction<?> active = activeHold();
        Minecraft minecraft = Minecraft.getInstance();
        long handle = minecraft.getWindow().getWindow();
        double scale = minecraft.getWindow().getGuiScale();

        double dx;
        double dy;
        MemoryStack stack = MemoryStack.stackPush();
        try {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(handle, x, y);
            dx = x.get(0) - lastRawX;
            dy = y.get(0) - lastRawY;
            lastRawX = pinX * scale;
            lastRawY = pinY * scale;
        } finally {
            stack.pop();
        }

        movePointerToPin(handle, minecraft);
        // Large packs reset the cursor mode on their own schedule; enforcing it here costs one GLFW call
        // per frame while a module is being held and is the difference between a hidden pointer and a
        // visible one fighting the drag.
        hideCursor(handle);
        if (pinSkipDelta) {
            pinSkipDelta = false;
            return;
        }
        if (active != null && (dx != 0.0D || dy != 0.0D)) {
            active.activeMouseMove(dx / scale, dy / scale);
        }
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
            pinPointer(hit);
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

        // Ctrl+wheel zooms; a bare wheel over a module adjusts it, the same way it does on the panel.
        if (hasControlDown()) {
            zoom = Mth.clamp(zoom + (float) scrollY * ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
            layout();
            return true;
        }

        Hit hit = hitAt(mouseX, mouseY);
        if (hit == null) {
            return true;
        }
        // Modules that take discrete wheel steps (Dashpanels Extra's scroll wheel) get the wheel first:
        // they move a whole notch by mutating their own position and having the server apply a tag, which
        // is exactly what their own client handler does - minus the screen check that stops it in a GUI.
        if (scrollModule(hit.module(), scrollY)) {
            return true;
        }
        // Everything else with a value is driven by a hold interaction, which speaks in mouse deltas.
        if (scrollMode && holdHit != null && holdHit.module() == hit.module()) {
            feedScroll(scrollY);
            return true;
        }
        endHold();
        activate(hit);
        if (holding != null) {
            scrollMode = true;
            feedScroll(scrollY);
        }
        return true;
    }

    /** Cached lookups for the modules that take discrete wheel steps; empty when the class has none. */
    private static final Map<Class<?>, Optional<Method>> SCROLL_STEPS = new HashMap<>();

    /**
     * Dashpanels Extra's scroll wheel is stepped by the wheel itself rather than by a drag: it exposes
     * {@code previewScrollStep(int)}, which returns the position the wheel would move to, and the
     * server applies it from the {@code position} tag of a module update. One notch is one step.
     */
    private static boolean scrollModule(Module module, double scrollY) {
        Optional<Method> cached = SCROLL_STEPS.computeIfAbsent(module.getClass(), type -> {
            try {
                return Optional.of(type.getMethod("previewScrollStep", int.class));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        if (cached.isEmpty()) {
            return false;
        }
        try {
            Object next = cached.get().invoke(module, scrollY > 0.0D ? 1 : -1);
            if (!(next instanceof Integer position)) {
                return false;
            }
            CompoundTag tag = new CompoundTag();
            tag.putInt("position", position);
            PacketDistributor.sendToServer(
                    new DefaultModuleUpdatePacket(module.getParentPos(), module.getName(), tag));
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Hands one notch of wheel travel to the interaction the way a drag would. Both axes get the same
     * amount with opposite signs: the knob family reads the horizontal delta and the scroll wheel reads
     * the vertical one, so a notch turns either of them in the "up means bigger" direction.
     */
    private void feedScroll(double scrollY) {
        ModuleHoldInteraction<?> active = activeHold();
        if (active != null) {
            double step = SCROLL_NOTCH * scrollY;
            active.activeMouseMove(step, -step);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}