package io.github.ondreamqwq.dbd.content;

import io.github.ondreamqwq.dbd.DbdRegistry;
import java.util.Objects;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A receiver for {@link PanelRemoteItem}: it joins the Dashpanels network like the panel linker does,
 * and remembers which panel it serves.
 *
 * <p>The panel is decided on the server (a client-side member never gets a network - see
 * {@code ModulesNetworkManager.handleAddingMember}, which returns immediately on a client) and then
 * pushed down through the vanilla block-entity update tag. The client needs it because the remote
 * screen renders the real panel block entity: that is the whole point of the projection, and it is
 * also why the terminal only works while the receiver's chunk is loaded on the client.
 */
public class PanelRemoteReceiverBlockEntity extends ModulesNetworkMember {

    /** NBT key for the served panel inside the block-entity update tag. */
    public static final String TAG_PANEL = "dbd:panel";

    /** The panel this receiver serves, or {@code null} while it is not on a panel network. */
    private BlockPos panelPos;

    public PanelRemoteReceiverBlockEntity(BlockPos pos, BlockState state) {
        super(DbdRegistry.PANEL_REMOTE_RECEIVER_BE.get(), pos, state);
    }

    /** The panel this receiver serves; only meaningful on the client once the update tag arrived. */
    public BlockPos panelPos() {
        return panelPos;
    }

    @Override
    public void tick(Level level, BlockPos pos, BlockState state) {
        super.tick(level, pos, state);
        if (level.isClientSide) {
            return;
        }
        BlockPos found = nearestPanel(pos);
        if (!Objects.equals(found, panelPos)) {
            panelPos = found;
            setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * The closest panel on this receiver's network.
     *
     * <p>Closest rather than "the only one" because one cable network can hold several panels, and
     * picking by distance keeps the choice stable instead of depending on member iteration order.
     */
    private BlockPos nearestPanel(BlockPos self) {
        ModulesNetwork network = getOrCreate();
        if (network == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ModulesNetworkMember member : network.members) {
            if (!(member instanceof AbstractPanelBlockEntity panel)) {
                continue;
            }
            BlockPos candidate = panel.getBlockPos();
            double distance = candidate.distSqr(self);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (panelPos != null) {
            tag.putLong(TAG_PANEL, panelPos.asLong());
        }
        return tag;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        panelPos = tag.contains(TAG_PANEL, Tag.TAG_LONG) ? BlockPos.of(tag.getLong(TAG_PANEL)) : null;
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (panelPos != null) {
            tag.putLong(TAG_PANEL, panelPos.asLong());
        }
    }
}
