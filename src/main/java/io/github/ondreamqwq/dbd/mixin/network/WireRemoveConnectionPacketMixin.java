package io.github.ondreamqwq.dbd.mixin.network;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import edn.stratodonut.drivebywire.network.WireRemoveConnectionPacket;
import io.github.ondreamqwq.dbd.event.ModuleWireHooks;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The other half: cutting or toggling a cable off releases the module binding. */
@Mixin(value = WireRemoveConnectionPacket.class, remap = false)
public abstract class WireRemoveConnectionPacketMixin {

    @Inject(method = "handle", at = @At("RETURN"), remap = false)
    private static void dbd$unbindModule(WireRemoveConnectionPacket payload, IPayloadContext context, CallbackInfo ci) {
        if (context.player() instanceof ServerPlayer player) {
            ModuleWireHooks.wireRemoved(player, player.level(), payload.source(), payload.sink(),
                    payload.direction(), payload.channel());
        }
    }
}
