package io.github.ondreamqwq.dbd.client;

import edn.stratodonut.drivebywire.items.WireItem;
import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.content.PanelLinkCenterBlock;
import io.github.ondreamqwq.dbd.net.RequestChannelsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opens the module picker with {@link DbdKeys#PICKER}, and arms the one-line hint that says so.
 *
 * <p>The trigger is deliberately "a cable selection is sitting on a panel link center" rather than
 * looking at the block: Drive By Wire keeps the selected source purely on the client, so the block
 * the player most recently picked with a cable is the only reliable notion of "the center I mean".
 *
 * <p>The hint is armed on the client right-click that selects a center, and is thrown away at the
 * end of that tick, so it only ever decorates the message Drive By Wire prints for that one click:
 * scrolling through channels and picking some other block stay silent.
 */
@EventBusSubscriber(modid = DbdMod.MOD_ID, value = Dist.CLIENT)
public final class CenterPickerKeys {

    private CenterPickerKeys() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) {
                return;
            }
            if (minecraft.screen != null) {
                // Keys are not queued while a screen is up, but drain anyway so a stale click cannot
                // reopen the picker the instant it closes.
                while (DbdKeys.PICKER.consumeClick()) {
                    // discard
                }
                return;
            }
            if (!DbdKeys.PICKER.consumeClick()) {
                return;
            }
            BlockPos source = WireSelection.source();
            if (source == null) {
                return;
            }
            if (!(minecraft.level.getBlockState(source).getBlock() instanceof PanelLinkCenterBlock)) {
                return;
            }
            PacketDistributor.sendToServer(new RequestChannelsPacket(source));
        } finally {
            // The right-click and the message it prints both happen before this tick ends.
            ChannelHint.disarm();
        }
    }

    /**
     * Remembers that this click is Drive By Wire *selecting* a link center, so its channel message
     * can carry one extra line.
     *
     * <p>Runs before Drive By Wire's own handler (highest priority): by the time it asks the block
     * for a channel, the selection it reports has already happened, so the flag has to be read from
     * the state before the click.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide().isServer()) {
            return;
        }
        if (!(event.getItemStack().getItem() instanceof WireItem)) {
            return;
        }
        // Re-picking the block that is already selected just deselects it; only a fresh pick prints
        // the message we want to decorate.
        if (WireSelection.source() != null) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof PanelLinkCenterBlock)) {
            return;
        }
        ChannelHint.arm();
    }
}
