package io.github.ondreamqwq.dbd.net;

import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.content.PanelLinkCenterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Ask the server what a panel link center can drive, so the client can open the picker.
 *
 * <p>Sent when the player presses the picker key while a cable selection is sitting on a center.
 * The client cannot answer this itself, see {@link ChannelListPacket}.
 *
 * @param pos the center that is currently selected with a cable
 */
public record RequestChannelsPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RequestChannelsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "request_channels"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestChannelsPacket> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, RequestChannelsPacket::pos, RequestChannelsPacket::new);

    /** How far away a player may open the picker, squared. */
    private static final double REACH = 64.0D * 64.0D;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestChannelsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(packet.pos().getCenter()) > REACH) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockEntity entity = level.getBlockEntity(packet.pos());
            if (!(entity instanceof PanelLinkCenterBlockEntity center)) {
                return;
            }
            PacketDistributor.sendToPlayer(player,
                    new ChannelListPacket(packet.pos(), center.outputModules(), center.target()));
        });
    }
}
