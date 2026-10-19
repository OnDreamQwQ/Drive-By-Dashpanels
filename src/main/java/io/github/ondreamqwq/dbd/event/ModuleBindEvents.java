package io.github.ondreamqwq.dbd.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import edn.stratodonut.drivebywire.items.WireItem;
import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import edn.stratodonut.drivebywire.wire.graph.WireNetworkNode;
import io.github.ondreamqwq.dbd.content.PanelBindings;
import io.github.ondreamqwq.dbd.content.PanelModuleBinding;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.io.IOutput;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Module level wire endpoints.
 *
 * <p>Drive By Wire decides everything about a cable click on the <em>client</em>
 * (its {@code onRightClickBlock} returns immediately on the server and sends a
 * packet instead), so the server never sees which source or channel the player
 * picked. What the server <em>does</em> see is the resulting graph change, so a
 * click on a module is resolved by diffing the wire graph around that panel
 * before and after the click:
 *
 * <ul>
 *   <li>a connection appeared &rarr; bind that source/channel to the module;</li>
 *   <li>a connection vanished &rarr; the player toggled the cable off, unbind.</li>
 * </ul>
 *
 * <p>Nothing here touches Drive By Wire internals beyond its public graph API.
 */
@EventBusSubscriber(modid = "drive_by_dashpanels")
public final class ModuleBindEvents {

    /** A wire interaction may arrive a tick or two after the click packet. */
    private static final int WAIT_TICKS = 20;

    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();

    private ModuleBindEvents() {
    }

    private record Endpoint(BlockPos source, String channel, BlockPos sink, Direction face) {
        String key() {
            return this.source.asLong() + "|" + this.channel;
        }
    }

    private record Pending(BlockPos panelPos, String module, Map<String, Endpoint> before, int age) {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof WireItem) || stack.isEmpty()) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof AbstractPanelBlockEntity panel)) {
            return;
        }
        String module = panel.getSelectedModule(player);
        if (module == null || module.isBlank()) {
            // No module under the crosshair: let Drive By Wire do its normal thing.
            return;
        }
        PENDING.put(player.getUUID(), new Pending(pos, module, endpoints(level, pos), 0));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PanelBindings.deliver();

        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PENDING.clear();
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Pending> entry = iterator.next();
            Pending pending = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (!(level.getBlockEntity(pending.panelPos()) instanceof AbstractPanelBlockEntity panel)) {
                iterator.remove();
                continue;
            }
            Map<String, Endpoint> after = endpoints(level, pending.panelPos());

            List<Endpoint> added = new ArrayList<>();
            for (Map.Entry<String, Endpoint> e : after.entrySet()) {
                if (!pending.before().containsKey(e.getKey())) {
                    added.add(e.getValue());
                }
            }
            if (!added.isEmpty()) {
                bind(player, panel, pending.module(), added.get(0));
                iterator.remove();
                continue;
            }

            List<Endpoint> removed = new ArrayList<>();
            for (Map.Entry<String, Endpoint> e : pending.before().entrySet()) {
                if (!after.containsKey(e.getKey())) {
                    removed.add(e.getValue());
                }
            }
            if (!removed.isEmpty()) {
                unbind(player, panel, pending.module(), removed);
                iterator.remove();
                continue;
            }

            if (pending.age() + 1 >= WAIT_TICKS) {
                say(player, "没有产生线缆连接：先拿线缆点一次信号源选中频道，再回来点模块");
                iterator.remove();
                continue;
            }
            entry.setValue(new Pending(pending.panelPos(), pending.module(), pending.before(), pending.age() + 1));
        }
    }

    private static void bind(ServerPlayer player, AbstractPanelBlockEntity panel, String module, Endpoint endpoint) {
        Module target = panel.getModule(module);
        if (!(target instanceof IOutput)) {
            say(player, "模块 " + module + " 不接收信号（只有指示灯泡、数码管这类输出模块可以）");
            return;
        }
        PanelModuleBinding previous = PanelBindings.get(panel, module);
        if (previous != null
                && previous.source().equals(endpoint.source())
                && previous.channel().equals(endpoint.channel())) {
            say(player, "模块 " + module + " 已经接在这个频道上了");
            return;
        }
        PanelBindings.resetValue(panel, module);
        PanelBindings.put(panel, module,
                new PanelModuleBinding(endpoint.source(), endpoint.channel(), endpoint.sink(), endpoint.face()));
        PanelBindings.markDirty(panel);
        say(player, "模块 " + module + "  <-  " + endpoint.channel());
    }

    private static void unbind(ServerPlayer player, AbstractPanelBlockEntity panel, String module,
            List<Endpoint> removed) {
        PanelModuleBinding binding = PanelBindings.get(panel, module);
        if (binding == null) {
            return;
        }
        boolean matches = removed.stream().anyMatch(endpoint ->
                endpoint.source().equals(binding.source()) && endpoint.channel().equals(binding.channel()));
        if (!matches) {
            return;
        }
        PanelBindings.unbind(panel, module);
        say(player, "已解除模块 " + module + " 的线缆连接");
    }

    /**
     * Every wire endpoint currently attached to {@code panelPos}. Both position
     * conventions are accepted, because Drive By Wire notifies sinks relative to
     * the endpoint face.
     */
    private static Map<String, Endpoint> endpoints(ServerLevel level, BlockPos panelPos) {
        Map<String, Endpoint> out = new LinkedHashMap<>();
        Map<Long, Map<String, java.util.Set<WireNetworkNode.WireNetworkSink>>> network =
                WireNetworkManager.get(level).getNetwork();
        for (Map.Entry<Long, Map<String, java.util.Set<WireNetworkNode.WireNetworkSink>>> sourceEntry
                : network.entrySet()) {
            BlockPos source = BlockPos.of(sourceEntry.getKey());
            if (source.equals(panelPos)) {
                continue;
            }
            for (Map.Entry<String, java.util.Set<WireNetworkNode.WireNetworkSink>> channelEntry
                    : sourceEntry.getValue().entrySet()) {
                for (WireNetworkNode.WireNetworkSink sink : channelEntry.getValue()) {
                    BlockPos sinkPos = BlockPos.of(sink.position());
                    Direction face = Direction.from3DDataValue(sink.direction());
                    if (!sinkPos.equals(panelPos) && !sinkPos.relative(face).equals(panelPos)) {
                        continue;
                    }
                    Endpoint endpoint = new Endpoint(source, channelEntry.getKey(), sinkPos, face);
                    out.put(endpoint.key(), endpoint);
                }
            }
        }
        return out;
    }

    private static void say(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.AQUA), true);
    }
}
