package io.github.ondreamqwq.dbd.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * One panel module listening to one Drive By Wire wire.
 *
 * <p>The wire endpoint is the panel block itself, so the pair
 * {@code (source, channel)} is what carries the value, and {@code sink} /
 * {@code face} is the exact endpoint the cable registered. Storing the endpoint
 * verbatim means removal never has to guess which face the player clicked.
 */
public record PanelModuleBinding(BlockPos source, String channel, BlockPos sink, Direction face) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("source", this.source.asLong());
        tag.putString("channel", this.channel);
        tag.putLong("sink", this.sink.asLong());
        tag.putInt("face", this.face.get3DDataValue());
        return tag;
    }

    public static PanelModuleBinding load(CompoundTag tag) {
        return new PanelModuleBinding(
                BlockPos.of(tag.getLong("source")),
                tag.getString("channel"),
                BlockPos.of(tag.getLong("sink")),
                Direction.from3DDataValue(tag.getInt("face")));
    }
}
