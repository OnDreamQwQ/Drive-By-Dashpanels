package io.github.ondreamqwq.dbd.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Decorates Drive By Wire's channel message with "press (key) to open the picker".
 *
 * <p>Drive By Wire prints that message from its own {@code changeChannel}, which cannot tell a
 * right-click from a scroll -- both go through it. So the flag is armed by the right-click listener
 * and consumed here, inside the mixin that intercepts the message call itself: this is the only spot
 * where the text is still under our control.
 */
public final class ChannelHint {

    private static boolean armed;

    private ChannelHint() {
    }

    public static void arm() {
        armed = true;
    }

    public static void disarm() {
        armed = false;
    }

    /**
     * Replaces {@code player.displayClientMessage(message, overlay)} inside Drive By Wire.
     *
     * <p>When the flag is armed the hint is appended to the very same component, rather than printed
     * as a second action bar line -- a second line would simply overwrite the first one.
     */
    public static void show(Player player, Component message, boolean overlay) {
        if (!armed) {
            player.displayClientMessage(message, overlay);
            return;
        }
        armed = false;
        player.displayClientMessage(message.copy().append(hint()), overlay);
    }

    private static Component hint() {
        return Component.translatable("gui.drive_by_dashpanels.channel_select.hint",
                DbdKeys.PICKER.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.AQUA));
    }
}
