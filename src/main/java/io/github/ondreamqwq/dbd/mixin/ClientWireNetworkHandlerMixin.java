package io.github.ondreamqwq.dbd.mixin;

import edn.stratodonut.drivebywire.client.ClientWireNetworkHandler;
import io.github.ondreamqwq.dbd.content.PanelLinkChannels;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one hook this addon needs.
 *
 * <p>{@code drivebywire} asks a {@link Block} which channel to use next, and a {@code Block} is a
 * singleton with no position, so it cannot look up the modules bound to a particular center.
 * {@code changeChannel} is the only place in the chain that still knows BOTH the block and its
 * {@code selectedSource} position, so the channel list is reloaded here, right before
 * {@code PanelLinkCenterBlock#wire$nextChannel} is consulted.
 */
@Mixin(ClientWireNetworkHandler.class)
public abstract class ClientWireNetworkHandlerMixin {

    @Shadow
    private static BlockPos selectedSource;

    @Inject(method = "changeChannel", at = @At("HEAD"))
    private static void dbd$reloadChannels(Block block, boolean forward, CallbackInfo ci) {
        PanelLinkChannels.refresh(selectedSource);
    }
}