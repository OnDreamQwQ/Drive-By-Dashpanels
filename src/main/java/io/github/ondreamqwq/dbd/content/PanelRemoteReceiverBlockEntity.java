package io.github.ondreamqwq.dbd.content;

import io.github.ondreamqwq.dbd.DbdRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkManager;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import moth.boxxed.panels.util.BaseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A receiver for {@link PanelRemoteItem}: it remembers which panels the remote should project.
 *
 * <p><b>It is deliberately not a {@code ModulesNetworkMember}.</b> Ledgering our block into
 * Dashpanels' network makes the game run its owning network's split/merge bookkeeping the moment this
 * block is broken: {@code ModulesNetwork.removeMember} rebuilds one candidate network per remaining
 * member and pushes {@code networkUpdate} down all of them, which re-sends block entity data for every
 * panel. With a receiver, a cable and a row of panels on one net that turned into a storm of block
 * updates across the whole row - break progress kept resetting (blocks looked unbreakable) and
 * right-clicking made the client re-sync (blocks appeared to vanish). A receiver contributes no
 * modules, so it has no business being a member: this class only *reads* the network through whichever
 * neighbour is a real member.
 *
 * <p>The panels are resolved on the server and pushed down through the vanilla block entity update
 * tag, because the remote screen renders the real panel block entities and therefore needs them on the
 * client as well.
 */
public class PanelRemoteReceiverBlockEntity extends BaseBlockEntity {

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
        List<BlockPos> found = nearbyPanels(level, pos);
        if (!found.equals(panels)) {
            panels = found;
            setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Every panel on the network this block touches, nearest first.
     *
     * <p>The network is found by asking a neighbouring member, never by joining one - see the class
     * comment for why. Order is by distance rather than by member iteration order, so the row in the
     * remote screen does not reshuffle when a member is added or removed.
     */
    private static List<BlockPos> nearbyPanels(Level level, BlockPos self) {
        ModulesNetwork network = networkAt(level, self);
        if (network == null) {
            return List.of();
        }
        List<BlockPos> found = new ArrayList<>();
        // Copy first: the panel's own tick may add members to this very set.
        for (ModulesNetworkMember member : List.copyOf(network.members)) {
            if (member instanceof AbstractPanelBlockEntity panel && !panel.isRemoved()) {
                found.add(panel.getBlockPos());
            }
        }
        found.sort(Comparator.comparingDouble(self::distSqr));
        return List.copyOf(found);
    }

    /** The network of any neighbouring Dashpanels member, or {@code null} when there is none. */
    private static ModulesNetwork networkAt(Level level, BlockPos self) {
        for (Direction side : Direction.values()) {
            if (level.getBlockEntity(self.relative(side)) instanceof ModulesNetworkMember member) {
                ModulesNetwork network = ModulesNetworkManager.getNetwork(member);
                if (network != null) {
                    return network;
                }
            }
        }
        return null;
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
