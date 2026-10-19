package io.github.ondreamqwq.dbd.event;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import io.github.ondreamqwq.dbd.content.PanelBindings;
import io.github.ondreamqwq.dbd.content.PanelModuleBinding;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.io.IOutput;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Wires bound straight to a Dashpanels module.
 *
 * <p>Nothing on the server ever sees the click itself: Drive By Wire resolves a cable click on the
 * client and only sends an "add / remove this connection" packet. Those two packets are therefore
 * the only place where the intent is still visible, so they are the hook -- by the time they return
 * the connection either exists or is gone, and the player who sent them knows which module they were
 * pointing at, because the panel keeps a per-player selected module fed by Dashpanels' own selection
 * packet.
 */
@EventBusSubscriber(modid = "drive_by_dashpanels")
public final class ModuleWireHooks {

    private ModuleWireHooks() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PanelBindings.deliver();
    }

    /** A cable just connected {@code sink}; bind it to the module this player is pointing at. */
    public static void wireAdded(ServerPlayer player, Level level, BlockPos source, BlockPos sink,
            Direction direction, String channel) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!WireNetworkManager.hasConnection(serverLevel, source, sink, direction, channel)) {
            return;
        }
        AbstractPanelBlockEntity panel = panelAt(serverLevel, sink, direction);
        if (panel == null) {
            return;
        }
        if (player.distanceToSqr(panel.getBlockPos().getCenter()) > 100.0D) {
            return;
        }
        String moduleName = panel.getSelectedModule(player);
        if (moduleName == null || moduleName.isBlank()) {
            say(player, "线缆连到面板上了，但没对准模块：对准要控制的模块再点一次");
            return;
        }
        Module module = panel.getModule(moduleName);
        if (!(module instanceof IOutput)) {
            say(player, "模块 " + moduleName + " 不接收信号（只有指示灯泡、数码管这类输出模块可以）");
            return;
        }
        PanelModuleBinding previous = PanelBindings.get(panel, moduleName);
        if (previous != null
                && previous.source().equals(source)
                && previous.channel().equals(channel)) {
            say(player, "模块 " + moduleName + " 已经接在这个频道上了");
            return;
        }
        PanelBindings.resetValue(panel, moduleName);
        PanelBindings.put(panel, moduleName, new PanelModuleBinding(source, channel, sink, direction));
        PanelBindings.markDirty(panel);
        say(player, "模块 " + moduleName + "  <-  " + channel);
    }

    /** A cable just disconnected {@code sink}; drop whatever was bound to it. */
    public static void wireRemoved(ServerPlayer player, Level level, BlockPos source, BlockPos sink,
            Direction direction, String channel) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (WireNetworkManager.hasConnection(serverLevel, source, sink, direction, channel)) {
            // Still there, so the packet was a no-op (wrong face, wrong channel, ...).
            return;
        }
        AbstractPanelBlockEntity panel = panelAt(serverLevel, sink, direction);
        if (panel == null) {
            return;
        }
        String matchName = null;
        for (var entry : PanelBindings.of(panel).entrySet()) {
            PanelModuleBinding binding = entry.getValue();
            if (binding.source().equals(source) && binding.channel().equals(channel)) {
                matchName = entry.getKey();
                break;
            }
        }
        if (matchName == null) {
            return;
        }
        PanelBindings.unbind(panel, matchName);
        say(player, "已解除模块 " + matchName + " 的线缆连接");
    }

    /**
     * The panel a cable landed on. Drive By Wire sends the clicked block, but a node may also be
     * recorded as the block in front of it, so both are tried.
     */
    private static AbstractPanelBlockEntity panelAt(Level level, BlockPos sink, Direction direction) {
        if (level.getBlockEntity(sink) instanceof AbstractPanelBlockEntity panel) {
            return panel;
        }
        BlockPos inFront = sink.relative(direction);
        if (level.getBlockEntity(inFront) instanceof AbstractPanelBlockEntity panel) {
            return panel;
        }
        return null;
    }

    private static void say(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.AQUA), true);
    }
}
