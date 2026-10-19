package io.github.ondreamqwq.dbd.mixin;

import edn.stratodonut.drivebywire.client.ClientWireNetworkHandler;
import io.github.ondreamqwq.dbd.client.ChannelHint;
import io.github.ondreamqwq.dbd.content.PanelLinkChannels;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hooks this addon needs inside Drive By Wire's client state.
 *
 * <p>{@code drivebywire} asks a {@link Block} which channel to use next, and a {@code Block} is a
 * singleton with no position, so it cannot look up the modules bound to a particular center.
 * {@code changeChannel} is the only place in the chain that still knows BOTH the block and its
 * {@code selectedSource} position, so the channel list is reloaded here, right before
 * {@code PanelLinkCenterBlock#wire$nextChannel} is consulted.
 *
 * <p>It is also the only place that prints the "channel selected" message, which is where the hint
 * about the picker key is appended -- see {@link ChannelHint}.
 */
@Mixin(ClientWireNetworkHandler.class)
public abstract class ClientWireNetworkHandlerMixin {

    @Shadow
    private static BlockPos selectedSource;

    @Inject(method = "changeChannel", at = @At("HEAD"))
    private static void dbd$reloadChannels(Block block, boolean forward, CallbackInfo ci) {
        PanelLinkChannels.refresh(selectedSource);
    }

    @Redirect(
            method = "changeChannel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private static void dbd$channelHint(Player player, Component message, boolean overlay) {
        ChannelHint.show(player, message, overlay);
    }
}
