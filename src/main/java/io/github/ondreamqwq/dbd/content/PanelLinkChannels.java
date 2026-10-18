package io.github.ondreamqwq.dbd.content;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * The wire-channel names of the block the player is currently wiring.
 *
 * <p>{@code drivebywire} asks a {@link net.minecraft.world.level.block.Block} which channel comes
 * next, and a block is a singleton with no position, so it cannot look the modules up itself. The
 * names arrive from the server through the block-entity update tag and are stashed here, keyed by
 * position; {@link #refresh(BlockPos)} selects the entry for the source the player just clicked.
 *
 * <p>Empty means "no names known yet", and the caller falls back to the world channel.
 */
public final class PanelLinkChannels {

    /** Fallback channel id used by drivebywire for "no channel selected". */
    public static final String WORLD = "world";

    /** Names per block position, pushed by {@code PanelLinkCenterBlockEntity#getUpdateTag}. */
    private static final Map<BlockPos, List<String>> BY_POS = new ConcurrentHashMap<>();

    /** Names of the block picked by the last {@link #refresh(BlockPos)}. */
    private static volatile List<String> channels = List.of();

    private PanelLinkChannels() {
    }

    public static List<String> get() {
        return channels;
    }

    /** Remember the names the server reported for {@code pos} (client side only). */
    public static void store(BlockPos pos, List<String> names) {
        if (pos == null) {
            return;
        }
        if (names == null || names.isEmpty()) {
            BY_POS.remove(pos);
        } else {
            // Guard against a server that keeps relocating centers: the map would otherwise grow.
            if (BY_POS.size() > 256) {
                BY_POS.clear();
            }
            BY_POS.put(pos.immutable(), List.copyOf(names));
        }
    }

    /** Select the names known for {@code pos}, or clear the stash when nothing is known. */
    public static void refresh(BlockPos pos) {
        channels = pos == null ? List.of() : BY_POS.getOrDefault(pos, List.of());
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
