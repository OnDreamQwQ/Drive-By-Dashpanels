package io.github.ondreamqwq.dbd.net;

import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.client.ChannelSelectScreen;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The channels a panel link center can put on the wire, sent back to the player who asked.
 *
 * <p>The list is server-only: {@code ModulesNetworkManager} never populates a client-side network,
 * so the client cannot work this out by itself.
 *
 * <p>Only <i>sending</i> channels travel. A module that merely receives a signal has nothing to
 * publish, so offering it would put a channel on the wire that always reads 0.
 *
 * @param pos the center the list belongs to
 * @param channels every sending module (multi-inputs split per sub-key), in panel order
 * @param current the channel currently picked, or an empty string for "all of them"
 */
public record ChannelListPacket(BlockPos pos, List<String> channels, String current)
        implements CustomPacketPayload {

    public static final Type<ChannelListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "channel_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChannelListPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                buf.writeVarInt(packet.channels().size());
                for (String name : packet.channels()) {
                    buf.writeUtf(name);
                }
                buf.writeUtf(packet.current());
            },
            buf -> {
                BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                int size = buf.readVarInt();
                List<String> channels = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    channels.add(buf.readUtf());
                }
                return new ChannelListPacket(pos, channels, buf.readUtf());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Opens the picker. Runs on the client, so the screen is created on the render thread. */
    public static void handle(ChannelListPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                ChannelSelectScreen.open(packet.pos(), packet.channels(), packet.current()));
    }
}
