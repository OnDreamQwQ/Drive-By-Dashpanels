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
 * The modules on a panel link center's network, sent back to the player who asked for the picker.
 *
 * <p>The module list is server-only: {@code ModulesNetworkManager} never populates a client-side
 * network, so the client cannot work this out by itself.
 *
 * <p>Two lists travel because the picker shows everything but only ticks what it can actually write
 * to: listing only {@code IOutput} modules made newly added modules look like they were missing.
 *
 * @param pos the center the list belongs to
 * @param modules every module name, in panel order
 * @param drivable the subset of {@code modules} a received signal can be written into
 * @param target the module currently selected, or an empty string
 */
public record ChannelListPacket(BlockPos pos, List<String> modules, List<String> drivable, String target)
        implements CustomPacketPayload {

    public static final Type<ChannelListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "channel_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChannelListPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                writeList(buf, packet.modules());
                writeList(buf, packet.drivable());
                buf.writeUtf(packet.target());
            },
            buf -> {
                BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                List<String> modules = readList(buf);
                List<String> drivable = readList(buf);
                return new ChannelListPacket(pos, modules, drivable, buf.readUtf());
            });

    private static void writeList(RegistryFriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        for (String value : values) {
            buf.writeUtf(value);
        }
    }

    private static List<String> readList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readUtf());
        }
        return values;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Opens the picker. Runs on the client, so the screen is created on the render thread. */
    public static void handle(ChannelListPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                ChannelSelectScreen.open(packet.pos(), packet.modules(), packet.drivable(), packet.target()));
    }
}
