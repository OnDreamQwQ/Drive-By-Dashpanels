package io.github.ondreamqwq.dbd.client;

import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.net.SelectTargetPacket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picks which module of the panel network a panel link center feeds.
 *
 * <p>The artwork is a re-cut of Dashpanels' own panel linker screen, so the layout constants below
 * are measured off the texture rather than copied from that screen: the window is still 220x158 at
 * texture (0,0), the row template lives at (0,155) and is 194x33, and both buttons are already drawn
 * as empty slots in the texture -- one in the row (where the linker had its delete button, now the
 * confirm tick) and one in the bottom bar (where the linker had its confirm button, now the exit).
 * The icons are therefore drawn straight into those slots, with no button frame of our own.
 *
 * <p>Every module on the network is listed, not just the ones a signal can be written into, so a
 * player who adds a module always sees it appear. Rows that cannot receive are greyed out and carry a
 * tooltip instead of a tick.
 */
public class ChannelSelectScreen extends Screen {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "textures/gui/channel_select.png");

    /**
     * Create's icon atlas. Dashpanels' panel linker draws both of its buttons from it, so reusing it
     * keeps the picker looking like the screen it was cut from.
     */
    private static final ResourceLocation ICONS =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/icons.png");
    private static final int ICON_ATLAS_SIZE = 256;

    /** {@code AllIcons.I_CONFIRM} -- a white tick, one row down in the atlas. */
    private static final int CHECK_U = 0;
    private static final int CHECK_V = 16;

    /** {@code AllIcons.I_MTD_CLOSE} -- a white cross. */
    private static final int EXIT_U = 16;
    private static final int EXIT_V = 144;

    private static final int WINDOW_WIDTH = 220;
    private static final int WINDOW_HEIGHT = 158;

    private static final int ROW_FIRST = 26;
    private static final int ROW_STEP = 35;
    private static final int ROW_U = 13;
    private static final int ROW_V = 155;
    private static final int ROW_WIDTH = 194;
    private static final int ROW_HEIGHT = 33;

    /** The check slot, in texture space inside the row template. */
    private static final int SLOT_X = 171;
    private static final int SLOT_Y = 165;
    private static final int SLOT_WIDTH = 16;
    private static final int SLOT_HEIGHT = 15;

    /** The exit slot, in texture space inside the bottom bar. */
    private static final int EXIT_X = 196;
    private static final int EXIT_Y = 135;

    private static final int LIST_TOP = 16;
    private static final int LIST_BOTTOM = 127;

    /** The exact colour and call style Dashpanels' panel linker uses for its own title. */
    private static final int TITLE_COLOR = 0x592424;
    private static final int NAME_COLOR = 0xFFFFFF;
    private static final int DISABLED_COLOR = 0x9A9A9A;
    private static final int EMPTY_COLOR = 0x7A5B45;

    private final BlockPos center;
    private final List<String> modules;
    private final Set<String> drivable;
    private final String target;

    private int leftPos;
    private int topPos;
    private int scroll;

    public ChannelSelectScreen(BlockPos center, List<String> modules, List<String> drivable, String target) {
        super(Component.translatable("gui.drive_by_dashpanels.channel_select"));
        this.center = center;
        this.modules = List.copyOf(modules);
        this.drivable = new HashSet<>(drivable);
        this.target = target;
    }

    /** Opens the picker, unless it is already up (the server may answer more than one request). */
    public static void open(BlockPos center, List<String> modules, List<String> drivable, String target) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ChannelSelectScreen) {
            return;
        }
        minecraft.setScreen(new ChannelSelectScreen(center, modules, drivable, target));
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - WINDOW_WIDTH) / 2;
        this.topPos = (this.height - WINDOW_HEIGHT) / 2;
        this.scroll = clampScroll(this.scroll);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);

        // Same as the panel linker: no shadow, so the dark brown title sits flat on the gold bar.
        String title = this.title.getString();
        int titleX = (WINDOW_WIDTH - this.font.width(title)) / 2;
        graphics.drawString(this.font, title, this.leftPos + titleX, this.topPos + 4, TITLE_COLOR, false);

        graphics.enableScissor(this.leftPos, this.topPos + LIST_TOP,
                this.leftPos + WINDOW_WIDTH, this.topPos + LIST_BOTTOM);
        if (this.modules.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.drive_by_dashpanels.channel_select.empty"),
                    this.leftPos + WINDOW_WIDTH / 2, this.topPos + ROW_FIRST + 24, EMPTY_COLOR);
        }
        for (int index = 0; index < this.modules.size(); index++) {
            renderRow(graphics, index, rowTop(index), mouseX, mouseY);
        }
        graphics.disableScissor();

        int exitX = this.leftPos + EXIT_X;
        int exitY = this.topPos + EXIT_Y;
        if (inside(mouseX, mouseY, exitX, exitY, SLOT_WIDTH, SLOT_HEIGHT)) {
            graphics.fill(exitX, exitY, exitX + SLOT_WIDTH, exitY + SLOT_HEIGHT, 0x40FFFFFF);
        }
        drawIcon(graphics, EXIT_U, EXIT_V, exitX, exitY);

        int hovered = rowAt(mouseX, mouseY);
        if (hovered >= 0 && !isDrivable(this.modules.get(hovered))) {
            graphics.renderTooltip(this.font,
                    Component.translatable("gui.drive_by_dashpanels.channel_select.no_output"),
                    mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRow(GuiGraphics graphics, int index, int rowY, int mouseX, int mouseY) {
        int rowX = this.leftPos + ROW_U;
        graphics.blit(TEXTURE, rowX, rowY, 0, ROW_V, ROW_WIDTH, ROW_HEIGHT);

        String name = this.modules.get(index);
        boolean can = isDrivable(name);
        if (name.equals(this.target)) {
            graphics.fill(rowX + 2, rowY + 2, rowX + ROW_WIDTH - 2, rowY + ROW_HEIGHT - 2, 0x33FFC400);
        }
        graphics.drawString(this.font, name, rowX + 8, rowY + 11,
                can ? NAME_COLOR : DISABLED_COLOR, false);
        if (!can) {
            return;
        }

        int slotX = rowX + SLOT_X;
        int slotY = rowY + SLOT_Y - ROW_V;
        if (inside(mouseX, mouseY, slotX, slotY, SLOT_WIDTH, SLOT_HEIGHT)) {
            graphics.fill(slotX, slotY, slotX + SLOT_WIDTH, slotY + SLOT_HEIGHT, 0x50FFFFFF);
        }
        drawIcon(graphics, CHECK_U, CHECK_V, slotX, slotY);
    }

    private static void drawIcon(GuiGraphics graphics, int u, int v, int x, int y) {
        graphics.blit(ICONS, x, y, 0, (float) u, (float) v, 16, 16, ICON_ATLAS_SIZE, ICON_ATLAS_SIZE);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (inside(mouseX, mouseY, this.leftPos + EXIT_X, this.topPos + EXIT_Y, SLOT_WIDTH, SLOT_HEIGHT)) {
                click();
                onClose();
                return true;
            }
            int index = slotAt(mouseX, mouseY);
            if (index >= 0 && isDrivable(this.modules.get(index))) {
                click();
                PacketDistributor.sendToServer(new SelectTargetPacket(this.center, this.modules.get(index)));
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        this.scroll = clampScroll(this.scroll - (int) Math.round(scrollY * ROW_STEP));
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing: the artwork frames itself and the world must stay at full brightness behind it.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isDrivable(String name) {
        return this.drivable.contains(name);
    }

    /** Top edge of a row in screen space, already scrolled. */
    private int rowTop(int index) {
        return this.topPos + ROW_FIRST + index * ROW_STEP - this.scroll;
    }

    /** Index of the row under the mouse, clipped to the list, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < this.leftPos + ROW_U || mouseX >= this.leftPos + ROW_U + ROW_WIDTH) {
            return -1;
        }
        if (mouseY < this.topPos + LIST_TOP || mouseY >= this.topPos + LIST_BOTTOM) {
            return -1;
        }
        for (int index = 0; index < this.modules.size(); index++) {
            int rowY = rowTop(index);
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                return index;
            }
        }
        return -1;
    }

    /** Index of the row whose check slot is under the mouse, or -1. */
    private int slotAt(double mouseX, double mouseY) {
        if (mouseY < this.topPos + LIST_TOP || mouseY > this.topPos + LIST_BOTTOM) {
            return -1;
        }
        for (int index = 0; index < this.modules.size(); index++) {
            int rowY = rowTop(index);
            int slotX = this.leftPos + ROW_U + SLOT_X;
            int slotY = rowY + SLOT_Y - ROW_V;
            if (inside(mouseX, mouseY, slotX, slotY, SLOT_WIDTH, SLOT_HEIGHT)) {
                return index;
            }
        }
        return -1;
    }

    /** Scrolled far enough that the last row is fully visible, not just its top edge. */
    private int clampScroll(int value) {
        int lastRowBottom = ROW_FIRST + Math.max(0, this.modules.size() - 1) * ROW_STEP + ROW_HEIGHT;
        int max = Math.max(0, lastRowBottom - LIST_BOTTOM);
        return Math.max(0, Math.min(value, max));
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
