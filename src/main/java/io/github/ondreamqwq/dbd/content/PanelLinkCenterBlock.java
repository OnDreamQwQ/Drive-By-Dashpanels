package io.github.ondreamqwq.dbd.content;

import edn.stratodonut.drivebywire.wire.MultiChannelWireSource;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Map;
import io.github.ondreamqwq.dbd.DbdRegistry;
import moth.boxxed.panels.api.network.ModulesNetworkMemberBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The panel link center: it sits on a Dashpanels cable network, reads every module on it, and
 * exposes one Drive By Wire channel per module.
 *
 * <p>The four boolean properties only exist so the model can grow a stub toward each neighbouring
 * cable; they follow {@code CableBlock} so the block joins the same connectivity graph.
 */
public class PanelLinkCenterBlock extends ModulesNetworkMemberBlock implements edn.stratodonut.drivebywire.wire.MultiChannelWireSource {

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;

    public static final Map<Direction, BooleanProperty> FACING =
            Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST,
                   Direction.SOUTH, SOUTH, Direction.WEST, WEST);

    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 16.0D, 15.0D);

    public PanelLinkCenterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, Boolean.FALSE)
                .setValue(EAST, Boolean.FALSE)
                .setValue(SOUTH, Boolean.FALSE)
                .setValue(WEST, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends PanelLinkCenterBlock> codec() {
        return simpleCodec(PanelLinkCenterBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PanelLinkCenterBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Connect to anything else that speaks the Dashpanels network - cables included. */
    @Override
    public boolean isConnecting(LevelReader level, BlockPos pos, BlockState state, Direction direction) {
        BlockState neighbour = level.getBlockState(pos.relative(direction));
        return neighbour.getBlock() instanceof ModulesNetworkMemberBlock;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Map.Entry<Direction, BooleanProperty> entry : FACING.entrySet()) {
            state = state.setValue(entry.getValue(),
                    isConnecting(context.getLevel(), context.getClickedPos(), state, entry.getKey()));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        BooleanProperty property = FACING.get(direction);
        if (property == null) {
            return state;
        }
        return state.setValue(property, isConnecting(level, pos, state, direction));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPE;
    }

    @Override
    public List<String> wire$getChannels() {
        return PanelLinkChannels.get();
    }

    @Override
    public String wire$nextChannel(String current, boolean forward) {
        return PanelLinkChannels.next(current, forward);
    }

    /** How many channel names the right-click report prints before it stops listing them. */
    private static final int REPORT_LIMIT = 8;

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof PanelLinkCenterBlockEntity center)) {
            return InteractionResult.SUCCESS;
        }
        String text;
        if (player.isShiftKeyDown()) {
            // Sneaking drops the bookkeeping a rename left behind, plus any cable link whose channel
            // no longer has a module behind it. Doing this automatically instead would delete real
            // wiring whenever a chunk with the panels unloads and the network looks empty for a tick.
            int aliases = center.clearAliases();
            int links = center.dropDanglingConnections();
            boolean unbound = center.clearTarget();
            text = "面板链接中心：已清理 " + aliases + " 条改名记录、" + links + " 条失效连接"
                    + (unbound ? "，并解除了接收目标" : "");
        } else if (center.network() == null) {
            text = "面板链接中心：没有接入 Dashpanels 网络（四周要有线缆或面板，且必须水平相邻）";
        } else {
            List<String> names = center.channelNames();
            String receiving = center.targetModule().isEmpty() ? "" : "，接收目标 " + center.targetModule();
            if (names.isEmpty()) {
                text = "面板链接中心：已接入网络，但网络里没有可读取的模块" + receiving;
            } else {
                List<String> shown = names.size() > REPORT_LIMIT ? names.subList(0, REPORT_LIMIT) : names;
                text = "面板链接中心：" + names.size() + " 个频道 -> " + String.join(", ", shown)
                        + (names.size() > REPORT_LIMIT ? " ..." : "") + receiving;
            }
        }
        player.displayClientMessage(Component.literal(text), false);
        return InteractionResult.SUCCESS;
    }
}