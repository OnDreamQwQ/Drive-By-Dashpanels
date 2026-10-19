package io.github.ondreamqwq.dbd.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The binding a {@link PanelRemoteItem} carries: which receiver block it drives, and in which
 * dimension.
 *
 * <p>Stored in the vanilla {@code minecraft:custom_data} component rather than a component of our
 * own, so a bound remote survives a reload, a server restart and being moved between inventories
 * without registering anything.
 */
public final class PanelRemoteData {

    private static final String ROOT = "dbd:receiver";
    private static final String POS = "pos";
    private static final String DIM = "dim";

    /** A bound remote: the dimension the receiver lives in, and where it sits. */
    public record Bound(ResourceLocation dimension, BlockPos pos) {
    }

    private PanelRemoteData() {
    }

    /** The receiver this remote is bound to, or {@code null} when it is not bound. */
    public static Bound get(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(ROOT, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag root = tag.getCompound(ROOT);
        if (!root.contains(POS, Tag.TAG_LONG) || !root.contains(DIM, Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(root.getString(DIM));
        if (dimension == null) {
            return null;
        }
        return new Bound(dimension, BlockPos.of(root.getLong(POS)));
    }

    public static void bind(ItemStack stack, ResourceLocation dimension, BlockPos pos) {
        CompoundTag root = new CompoundTag();
        root.putLong(POS, pos.asLong());
        root.putString(DIM, dimension.toString());
        stack.set(DataComponents.CUSTOM_DATA, customData(stack, root));
    }

    public static void unbind(ItemStack stack) {
        stack.set(DataComponents.CUSTOM_DATA, customData(stack, null));
        if (stack.get(DataComponents.CUSTOM_DATA) != null
                && stack.get(DataComponents.CUSTOM_DATA).copyTag().isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        }
    }

    /** The tag with our key replaced, keeping any other custom data the stack already carried. */
    private static CustomData customData(ItemStack stack, CompoundTag replacement) {
        CompoundTag tag = new CompoundTag();
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        if (existing != null) {
            tag = existing.copyTag();
        }
        tag.remove(ROOT);
        if (replacement != null) {
            tag.put(ROOT, replacement);
        }
        return CustomData.of(tag);
    }
}
