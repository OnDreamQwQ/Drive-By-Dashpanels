package io.github.ondreamqwq.dbd.content;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The wire-channel names that the block under the crosshair currently offers.
 *
 * <p>{@code drivebywire} picks a channel through {@code MultiChannelWireSource#wire$nextChannel},
 * and it calls that on the {@link net.minecraft.world.level.block.Block} instance - which carries
 * no position, so it cannot know what modules the center is bound to. The only place the rest of
 * the chain ever sees both the block and its position is
 * {@code ClientWireNetworkHandler#changeChannel(Block, boolean)}, which reads a static
 * {@code selectedSource} field. A one-method mixin into that call copies the channel list out of
 * the block entity before the scroll is applied, and the block then answers from this stash.
 *
 * <p>Empty means "not a panel link center", and the caller falls back to the world channel.
 */
public final class PanelLinkChannels {

    /** Fallback channel id used by drivebywire for "no channel selected". */
    public static final String WORLD = "world";

    private static volatile List<String> channels = List.of();

    private PanelLinkChannels() {
    }

    public static List<String> get() {
        return channels;
    }

    /** Reload the stash for the block at {@code pos}, or clear it when that is not a center. */
    public static void refresh(BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        if (pos == null || level == null) {
            channels = List.of();
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        channels = be instanceof PanelLinkCenterBlockEntity center
                ? List.copyOf(center.channelNames())
                : List.of();
    }

    /** Mirrors the scroll behaviour of drivebywire's own multi-channel blocks. */
    public static String next(String current, boolean forward) {
        List<String> list = channels;
        if (list.isEmpty()) {
            return WORLD;
        }
        int index = current == null ? -1 : list.indexOf(current);
        if (index < 0) {
            return forward ? list.get(0) : list.get(list.size() - 1);
        }
        return list.get(Math.floorMod(index + (forward ? 1 : -1), list.size()));
    }
}