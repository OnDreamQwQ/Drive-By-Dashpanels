package io.github.ondreamqwq.dbd.content;

import io.github.ondreamqwq.dbd.client.PanelRemoteScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The handheld end of the panel remote.
 *
 * <ul>
 *   <li>sneak + right click a {@link PanelRemoteReceiverBlock} - bind this remote to that receiver</li>
 *   <li>sneak + right click the air - forget the binding</li>
 *   <li>plain right click - open the projection of the panel behind the bound receiver</li>
 * </ul>
 *
 * <p>The screen is opened on the client only: the projection renders the real panel block entity,
 * so the client has to have that chunk loaded anyway. That is also the honest limit of the feature
 * right now - a panel in an unloaded chunk cannot be projected, and the player is told so instead of
 * being shown an empty frame.
 */
public class PanelRemoteItem extends Item {

    public PanelRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        PanelRemoteData.Bound bound = PanelRemoteData.get(stack);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                if (bound == null) {
                    tell(player, "remote.not_bound", ChatFormatting.YELLOW);
                } else {
                    PanelRemoteData.unbind(stack);
                    tell(player, "remote.unbound", ChatFormatting.GRAY);
                }
            }
            return InteractionResultHolder.success(stack);
        }

        if (!level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (bound == null) {
            tell(player, "remote.not_bound", ChatFormatting.YELLOW);
            return InteractionResultHolder.success(stack);
        }
        if (!bound.dimension().equals(level.dimension().location())) {
            tell(player, "remote.wrong_dimension", ChatFormatting.RED);
            return InteractionResultHolder.success(stack);
        }
        if (!(level.getBlockEntity(bound.pos()) instanceof PanelRemoteReceiverBlockEntity receiver)) {
            tell(player, "remote.too_far", ChatFormatting.RED);
            return InteractionResultHolder.success(stack);
        }
        if (receiver.panels().isEmpty()) {
            tell(player, "remote.no_panel", ChatFormatting.YELLOW);
            return InteractionResultHolder.success(stack);
        }
        PanelRemoteScreen.open(bound.pos());
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof PanelRemoteReceiverBlockEntity receiver)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        PanelRemoteData.bind(context.getItemInHand(), level.dimension().location(), context.getClickedPos());
        tell(player, "remote.bound", ChatFormatting.GREEN);
        if (receiver.panelPos() == null) {
            tell(player, "remote.bound_without_panel", ChatFormatting.YELLOW);
        }
        return InteractionResult.SUCCESS;
    }

    private static void tell(Player player, String key, ChatFormatting colour) {
        player.displayClientMessage(
                Component.translatable("gui.drive_by_dashpanels." + key).withStyle(colour), true);
    }
}
