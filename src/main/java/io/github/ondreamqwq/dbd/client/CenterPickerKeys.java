package io.github.ondreamqwq.dbd.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.content.PanelLinkCenterBlock;
import io.github.ondreamqwq.dbd.net.RequestChannelsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Opens the module picker with Alt.
 *
 * <p>The trigger is deliberately "a cable selection is sitting on a panel link center" rather than
 * looking at the block: Drive By Wire keeps the selected source purely on the client, so the block
 * the player most recently picked with a cable is the only reliable notion of "the center I mean".
 *
 * <p>The key is edge triggered, and the "already pressed" latch survives a screen being open, so
 * closing the picker while Alt is still held does not immediately reopen it.
 */
@EventBusSubscriber(modid = DbdMod.MOD_ID, value = Dist.CLIENT)
public final class CenterPickerKeys {

    private static boolean altDown;

    private CenterPickerKeys() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            altDown = false;
            return;
        }
        Window window = minecraft.getWindow();
        boolean down = InputConstants.isKeyDown(window.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
        if (!down) {
            altDown = false;
            return;
        }
        if (altDown) {
            return;
        }
        altDown = true;
        if (minecraft.screen != null) {
            return;
        }
        BlockPos source = WireSelection.source();
        if (source == null) {
            return;
        }
        if (!(minecraft.level.getBlockState(source).getBlock() instanceof PanelLinkCenterBlock)) {
            return;
        }
        PacketDistributor.sendToServer(new RequestChannelsPacket(source));
    }
}
