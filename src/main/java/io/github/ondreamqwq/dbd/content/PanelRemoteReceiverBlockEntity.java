package io.github.ondreamqwq.dbd.content;

import io.github.ondreamqwq.dbd.DbdRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A receiver for {@link PanelRemoteItem}: it joins the Dashpanels network like the panel linker does,
 * and remembers every panel it serves.
 *
 * <p>The panels are decided on the server (a client-side member never gets a network - see
 * {@code ModulesNetworkManager.handleAddingMember}, which returns immediately on a client) and then
 * pushed down through the vanilla block-entity update tag. The client needs them because the remote
 * screen renders the real panel block entities: that is the whole point of the projection, and it is
 * also why the terminal only works while the receiver's chunk is loaded on the client.
 */
public class PanelRemoteReceiverBlockEntity extends ModulesNetworkMember {

    /** NBT key for the served panels inside the block-entity update tag. */
    public static final String TAG_PANELS = "dbd:panels";

    /** Every panel on this receiver's network, nearest first. Empty while it is not on one. */
    private List<BlockPos> panels = List.of();

    public PanelRemoteReceiverBlockEntity(BlockPos pos, BlockState state) {
        super(DbdRegistry.PANEL_REMOTE_RECEIVER_BE.get(), pos, state);
    }

    /** The panels this receiver serves; only meaningful on the client once the update tag arrived. */
    public List<BlockPos> panels() {
        return panels;
    }

    /** The nearest panel, or {@code null} when there is none. */
    public BlockPos panelPos() {
        return panels.isEmpty() ? null : panels.getFirst();
    }

    /** Whether {@code pos} is one of the panels this receiver serves. */
    public boolean covers(BlockPos pos) {
        return panels.contains(pos);
    }

    @Override
    public void tick(Level level, BlockPos pos, BlockState state) {
        super.tick(level, pos, state);
        if (level.isClientSide) {
            return;
        }
        List<BlockPos> found = nearbyPanels(pos);
        if (!found.equals(panels)) {
            panels = found;
            setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Every panel on this receiver's network, nearest first.
     *
     * <p>The order is by distance rather than by member iteration order, so the row of panels in the
     * remote screen does not reshuffle when a member is added or removed.
     */
    private List<BlockPos> nearbyPanels(BlockPos self) {
        ModulesNetwork network = getOrCreate();
        if (network == null) {
            return List.of();
        }
        List<BlockPos> found = new ArrayList<>();
        for (ModulesNetworkMember member : network.members) {
            if (member instanceof AbstractPanelBlockEntity panel) {
                found.add(panel.getBlockPos());
            }
        }
        found.sort(Comparator.comparingDouble(self::distSqr));
        return List.copyOf(found);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put(TAG_PANELS, new LongArrayTag(panels.stream().mapToLong(BlockPos::asLong).toArray()));
        return tag;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        panels = readPanels(tag);
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_PANELS, new LongArrayTag(panels.stream().mapToLong(BlockPos::asLong).toArray()));
    }

    private static List<BlockPos> readPanels(CompoundTag tag) {
        if (!tag.contains(TAG_PANELS, Tag.TAG_LONG_ARRAY)) {
            return List.of();
        }
        long[] raw = tag.getLongArray(TAG_PANELS);
        List<BlockPos> out = new ArrayList<>(raw.length);
        for (long packed : raw) {
            out.add(BlockPos.of(packed));
        }
        return List.copyOf(out);
    }

}

