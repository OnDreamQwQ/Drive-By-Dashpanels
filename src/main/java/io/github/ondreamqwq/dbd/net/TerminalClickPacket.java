package io.github.ondreamqwq.dbd.net;

import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.content.PanelRemoteReceiverBlockEntity;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * "The player clicked this module in the projection."
 *
 * <p>The client knows which module is under the cursor because it does the projection with the real
 * panel block entity, so all this packet carries is the name. The server re-checks that the panel is
 * one the receiver still serves and that the named module still exists, then runs the module's own
 * {@code onUse} - the same entry point a right click on the panel would use.
 *
 * @param receiver the receiver the remote is bound to
 * @param panel    the panel the receiver had resolved when the screen opened
 * @param module   the module name that was clicked
 */
public record TerminalClickPacket(BlockPos receiver, BlockPos panel, String module)
        implements CustomPacketPayload {

    public static final Type<TerminalClickPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DbdMod.MOD_ID, "terminal_click"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalClickPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TerminalClickPacket::receiver,
                    BlockPos.STREAM_CODEC, TerminalClickPacket::panel,
                    ByteBufCodecs.STRING_UTF8, TerminalClickPacket::module,
                    TerminalClickPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TerminalClickPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockEntity receiverEntity = level.getBlockEntity(packet.receiver());
            if (!(receiverEntity instanceof PanelRemoteReceiverBlockEntity receiver)) {
                return;
            }
            if (!receiver.covers(packet.panel())) {
                return;
            }
            BlockEntity panelEntity = level.getBlockEntity(packet.panel());
            if (!(panelEntity instanceof AbstractPanelBlockEntity panel)) {
                return;
            }
            Module module = panel.getModule(packet.module());
            if (module == null) {
                return;
            }
            module.onUse(level, player);
            panel.setChanged();
        });
    }
}
