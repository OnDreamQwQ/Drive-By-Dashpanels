package io.github.ondreamqwq.dbd.content;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Which panel link center each player last touched with a Drive By Wire cable.
 *
 * <p>That click is what arms the center as the target of the next module bind: laying a cable on a
 * center is already the natural "I mean this one" gesture, so it is reused instead of adding a
 * separate tool. The map is server side only -- it is a piece of in-flight player intent, not world
 * state, so it is deliberately not saved.
 */
public final class PanelLinkCenterBinding {

    private static final Map<UUID, BlockPos> ARMED = new HashMap<>();

    private PanelLinkCenterBinding() {
    }

    /** Remember {@code pos} as the center this player will bind a module to. */
    public static void arm(Player player, BlockPos pos) {
        ARMED.put(player.getUUID(), pos.immutable());
    }

    /** The armed center, or {@code null} when the player has not picked one. */
    public static BlockPos armed(Player player) {
        return ARMED.get(player.getUUID());
    }

    /** Forget the armed center. */
    public static void clear(Player player) {
        ARMED.remove(player.getUUID());
    }

    /**
     * Whether {@code stack} is Drive By Wire's cable. Matched by registry name rather than by class so
     * a rename inside Drive By Wire cannot break loading of this mod.
     */
    public static boolean isCable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "drivebywire".equals(id.getNamespace()) && "wire".equals(id.getPath());
    }
}
