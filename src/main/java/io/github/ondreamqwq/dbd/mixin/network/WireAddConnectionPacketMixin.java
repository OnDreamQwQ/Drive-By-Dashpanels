package io.github.ondreamqwq.dbd.mixin.network;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import edn.stratodonut.drivebywire.network.WireAddConnectionPacket;
import io.github.ondreamqwq.dbd.event.ModuleWireHooks;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Turns Drive By Wire's own "connect this cable" packet into a module binding.
 *
 * <p>This is the only server-side moment where a cable click is still attributable to a player: the
 * client resolves the click locally and never sends a block-use packet when a cable is in hand, so a
 * {@code PlayerInteractEvent} listener on the server would never fire.
 */
@Mixin(value = WireAddConnectionPacket.class, remap = false)
public abstract class WireAddConnectionPacketMixin {

    @Inject(method = "handle", at = @At("RETURN"), remap = false)
    private static void dbd$bindModule(WireAddConnectionPacket payload, IPayloadContext context, CallbackInfo ci) {
        if (context.player() instanceof ServerPlayer player) {
            ModuleWireHooks.wireAdded(player, player.level(), payload.source(), payload.sink(),
                    payload.direction(), payload.channel());
        }
    }
}
