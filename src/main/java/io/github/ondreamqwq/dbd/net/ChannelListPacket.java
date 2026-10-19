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
 * The modules a panel link center can drive, sent back to the player who asked for the picker.
 *
 * <p>The module list is server-only: {@code ModulesNetworkManager} never populates a client-side
 * network, so the client cannot work this out by itself. Only modules that implement
 * {@code IOutput} are listed, because those are the only ones a received signal can be written to.
 *
 * @param pos the center the list belongs to
 * @param modules the drivable module names, in panel order
 * @param target the module currently selected, or an empty string
 */
public record ChannelListPacket(BlockPos pos, List<String> modules, String target) implements CustomPacketPayload {

    public static final Type<ChannelListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "channel_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChannelListPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                buf.writeVarInt(packet.modules().size());
                for (String name : packet.modules()) {
                    buf.writeUtf(name);
                }
                buf.writeUtf(packet.target());
            },
            buf -> {
                BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                int size = buf.readVarInt();
                List<String> modules = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    modules.add(buf.readUtf());
                }
                return new ChannelListPacket(pos, modules, buf.readUtf());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Opens the picker. Runs on the client, so the screen is created on the render thread. */
    public static void handle(ChannelListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChannelSelectScreen.open(packet.pos(), packet.modules(), packet.target()));
    }
}
