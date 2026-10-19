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
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Pick which module of the panel network this center feeds, or clear the choice with an empty name.
 *
 * <p>The name is validated against the center's own drivable list, so a stale click cannot leave the
 * center pointing at a module that was removed in the meantime.
 */
public record SelectTargetPacket(BlockPos pos, String module) implements CustomPacketPayload {

    public static final Type<SelectTargetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "select_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelectTargetPacket> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, SelectTargetPacket::pos,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, SelectTargetPacket::module,
                    SelectTargetPacket::new);

    private static final double REACH = 64.0D * 64.0D;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SelectTargetPacket packet, IPayloadContext context) {
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
            String wanted = packet.module();
            // The picker closes the moment the tick is clicked, so nothing is sent back: a refreshed
            // list would only reopen the screen the player just dismissed.
            String applied = wanted.isEmpty() || center.outputModules().contains(wanted) ? wanted : center.target();
            center.setTarget(applied);
        });
    }
}
