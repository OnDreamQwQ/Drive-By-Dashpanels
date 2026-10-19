package io.github.ondreamqwq.dbd.event;

import io.github.ondreamqwq.dbd.DbdMod;
import io.github.ondreamqwq.dbd.content.PanelLinkCenterBinding;
import io.github.ondreamqwq.dbd.content.PanelLinkCenterBlockEntity;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wires a panel module up to a panel link center, using the module selection the Dashpanels renderer
 * already draws.
 *
 * <p>Dashpanels' {@code AbstractPanelRenderer} ray-casts every module of a panel against the player's
 * view every frame and pushes the one under the crosshair to the server through
 * {@code SelectedModulePacket}, so the server can simply ask the panel which module is boxed. The
 * box therefore needs no new code here -- it appears on its own.
 *
 * <p>Two gestures, both with the Drive By Wire cable in hand:
 * <ul>
 *   <li>plain click on a center: arms it, and is otherwise left to Drive By Wire so the cable can be
 *       laid in the same motion;</li>
 *   <li>sneak click on a panel: binds the boxed module to the armed center. The event is cancelled so
 *       neither the cable nor the module itself reacts to it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DbdMod.MOD_ID)
public final class PanelBindingEvents {

    private PanelBindingEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (!PanelLinkCenterBinding.isCable(held)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        Player player = event.getEntity();

        if (!player.isShiftKeyDown()) {
            // Keep out of the way: Drive By Wire still owns this click. Arming is a side effect.
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof PanelLinkCenterBlockEntity center) {
                boolean changed = !center.getBlockPos().equals(PanelLinkCenterBinding.armed(player));
                PanelLinkCenterBinding.arm(player, center.getBlockPos());
                if (changed) {
                    player.displayClientMessage(Component.literal(
                            "面板链接中心：已选为接收目标 —— 潜行右键面板上的模块即可绑定"), true);
                }
            }
            return;
        }

        if (!(level.getBlockEntity(pos) instanceof AbstractPanelBlockEntity panel)) {
            return;
        }
        // Sneaking means "bind", so neither the module's own click handling nor a new cable between
        // the panel and something else may happen on this press.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide) {
            return;
        }
        bind(player, level, panel);
    }

    private static void bind(Player player, Level level, AbstractPanelBlockEntity panel) {
        String module = panel.getSelectedModule(player);
        if (module == null || module.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "没有选中模块：先把准心对准面板上的模块，看到蓝框后再潜行右键"), true);
            return;
        }
        BlockPos armed = PanelLinkCenterBinding.armed(player);
        if (armed == null) {
            player.displayClientMessage(Component.literal(
                    "先拿线缆右键一个面板链接中心，再回来绑定"), true);
            return;
        }
        if (!(level.getBlockEntity(armed) instanceof PanelLinkCenterBlockEntity center)) {
            PanelLinkCenterBinding.clear(player);
            player.displayClientMessage(Component.literal(
                    "上次选的面板链接中心不在了，请重新选一个"), true);
            return;
        }
        String problem = center.bindModule(module);
        if (problem != null) {
            player.displayClientMessage(Component.literal(problem), true);
            return;
        }
        player.displayClientMessage(Component.literal(
                "面板链接中心：接收目标已设为 " + module), true);
    }
}
