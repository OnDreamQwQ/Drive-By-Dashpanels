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
 *
 * <p>A cable is not limited to one module: the binding table remembers one entry per module, all of
 * them fed by the same source and channel. Since a second click on the same cable makes Drive By Wire
 * remove it, a removal is interpreted by what the player is aiming at -- the module it already feeds
 * is released, any other module is added instead, and the cable is re-created as long as anything is
 * still bound to it. Sneaking takes the cable out for good.
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
        if (panel == null || tooFar(player, panel)) {
            return;
        }
        String moduleName = selected(panel, player);
        if (moduleName == null) {
            return;
        }
        if (!(panel.getModule(moduleName) instanceof IOutput)) {
            say(player, "模块 " + moduleName + " 不接收信号（只有指示灯泡、数码管这类输出模块可以）");
            return;
        }
        PanelModuleBinding previous = PanelBindings.get(panel, moduleName);
        if (previous != null && previous.source().equals(source) && previous.channel().equals(channel)) {
            say(player, "模块 " + moduleName + " 已经接在 " + channel + " 上了");
            return;
        }
        bind(panel, moduleName, new PanelModuleBinding(source, channel, sink, direction));
        say(player, describe(panel, source, channel, moduleName));
    }

    /**
     * The cable just came off {@code sink}. Which module that concerns is decided by the selection:
     * the one it already feeds is released, anything else is added.
     */
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
        if (player.isShiftKeyDown()) {
            int dropped = PanelBindings.unbindChannel(panel, source, channel);
            say(player, dropped > 0
                    ? "已解除 " + dropped + " 个模块的线缆连接"
                    : "已断开线缆");
            return;
        }
        String moduleName = selected(panel, player);
        if (moduleName == null) {
            // Aiming at nothing: keep the cable if modules still depend on it.
            if (keepAlive(serverLevel, panel, source, sink, direction, channel)) {
                say(player, "这条线缆还控制着 " + PanelBindings.boundTo(panel, source, channel).size()
                        + " 个模块：潜行切断可一并解除");
            }
            return;
        }
        PanelModuleBinding current = PanelBindings.get(panel, moduleName);
        boolean sameChannel = current != null
                && current.source().equals(source)
                && current.channel().equals(channel);
        if (sameChannel) {
            PanelBindings.unbind(panel, moduleName);
            say(player, "已解除模块 " + moduleName + " 的线缆连接");
        } else {
            if (!(panel.getModule(moduleName) instanceof IOutput)) {
                say(player, "模块 " + moduleName + " 不接收信号（只有指示灯泡、数码管这类输出模块可以）");
                return;
            }
            bind(panel, moduleName, new PanelModuleBinding(source, channel, sink, direction));
        }
        if (!keepAlive(serverLevel, panel, source, sink, direction, channel)) {
            PanelBindings.unbind(panel, moduleName);
            say(player, "线缆连接已断开");
            return;
        }
        if (!sameChannel) {
            say(player, describe(panel, source, channel, moduleName));
        }
    }

    /** Put the cable back when something still reads from it; returns true if it is connected. */
    private static boolean keepAlive(ServerLevel level, AbstractPanelBlockEntity panel, BlockPos source,
            BlockPos sink, Direction direction, String channel) {
        if (!PanelBindings.uses(panel, source, channel)) {
            return false;
        }
        if (WireNetworkManager.hasConnection(level, source, sink, direction, channel)) {
            return true;
        }
        WireNetworkManager.createConnection(level, source, sink, direction, channel);
        return WireNetworkManager.hasConnection(level, source, sink, direction, channel);
    }

    private static void bind(AbstractPanelBlockEntity panel, String moduleName, PanelModuleBinding binding) {
        PanelBindings.resetValue(panel, moduleName);
        PanelBindings.put(panel, moduleName, binding);
        PanelBindings.markDirty(panel);
    }

    private static String describe(AbstractPanelBlockEntity panel, BlockPos source, String channel,
            String moduleName) {
        int count = PanelBindings.boundTo(panel, source, channel).size();
        String extra = count > 1 ? "（这个频道同时供给 " + count + " 个模块）" : "";
        return "模块 " + moduleName + "  <-  " + channel + extra;
    }

    private static String selected(AbstractPanelBlockEntity panel, ServerPlayer player) {
        if (tooFar(player, panel)) {
            return null;
        }
        String moduleName = panel.getSelectedModule(player);
        if (moduleName == null || moduleName.isBlank()) {
            return null;
        }
        return moduleName;
    }

    private static boolean tooFar(ServerPlayer player, AbstractPanelBlockEntity panel) {
        return player.distanceToSqr(panel.getBlockPos().getCenter()) > 100.0D;
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
