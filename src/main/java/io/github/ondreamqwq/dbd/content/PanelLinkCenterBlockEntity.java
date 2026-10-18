package io.github.ondreamqwq.dbd.content;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.ondreamqwq.dbd.DbdRegistry;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.ModuleMap;
import moth.boxxed.panels.api.module.io.IInput;
import moth.boxxed.panels.api.module.io.IMultiInput;
import moth.boxxed.panels.api.module.io.ModuleIOInfo;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Joins the Dashpanels {@code ModulesNetwork} exactly like Dashpanels' own panel linker, so it can
 * read the live module map of every panel on the same cable network.
 *
 * <p>Every tick it publishes each module's analog value (0-15) into the Drive By Wire wire network
 * under the module's own name, so the module names become the cable channels. A channel that
 * disappears is written back to 0 once, so a removed module does not leave a stuck signal behind.
 *
 * <p>The module names are computed on the SERVER only: {@code ModulesNetworkManager.handleAddingMember}
 * returns immediately on a client, so a client-side block entity never has a network to read. The
 * names therefore travel to the client through the vanilla block-entity update tag, which is what
 * makes {@code PanelLinkCenterBlock#wire$nextChannel} able to answer while the player scrolls.
 */
public class PanelLinkCenterBlockEntity extends ModulesNetworkMember {

    /** NBT key for the channel names inside the block-entity update tag. */
    public static final String TAG_CHANNELS = "dbd:channels";

    /** Values published on the previous tick, used to clear channels whose module went away. */
    private List<String> published = List.of();

    /** Channel names last pushed to clients, so the update tag is only resent when they change. */
    private List<String> synced = List.of();

    public PanelLinkCenterBlockEntity(BlockPos pos, BlockState state) {
        super(DbdRegistry.PANEL_LINK_CENTER_BE.get(), pos, state);
    }

    /** The cable channels this center offers, one per module (and one per multi-input sub-key). */
    public List<String> channelNames() {
        return List.copyOf(sample().keySet());
    }

    /** Server-side view of the cable network this center joined, or {@code null} when it has none. */
    public ModulesNetwork network() {
        return getOrCreate();
    }

    @Override
    public void tick(Level level, BlockPos pos, BlockState state) {
        super.tick(level, pos, state);
        if (level.isClientSide) {
            return;
        }
        Map<String, Integer> now = sample();
        List<String> names = List.copyOf(now.keySet());
        if (!names.equals(synced)) {
            synced = names;
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
        publish(level, pos, now);
    }

    @Override
    public void remove() {
        Level level = getLevel();
        if (level != null && !level.isClientSide) {
            for (String channel : published) {
                WireNetworkManager.trySetSignalAt(level, getBlockPos(), channel, 0);
            }
            published = List.of();
        }
        super.remove();
    }

    // ------------------------------------------------------------------ client sync

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        ListTag list = new ListTag();
        for (String name : channelNames()) {
            list.add(StringTag.valueOf(name));
        }
        tag.put(TAG_CHANNELS, list);
        return tag;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        List<String> names = readChannels(tag);
        synced = names;
        if (level != null && level.isClientSide) {
            PanelLinkChannels.store(worldPosition, names);
        }
    }

    private static List<String> readChannels(CompoundTag tag) {
        if (!tag.contains(TAG_CHANNELS, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = tag.getList(TAG_CHANNELS, Tag.TAG_STRING);
        List<String> names = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            names.add(list.getString(i));
        }
        return names;
    }

    // ------------------------------------------------------------------ signals

    private void publish(Level level, BlockPos pos, Map<String, Integer> now) {
        WireNetworkManager network = WireNetworkManager.get(level);
        if (network == null) {
            return;
        }
        for (String stale : published) {
            if (!now.containsKey(stale)) {
                WireNetworkManager.trySetSignalAt(level, pos, stale, 0);
            }
        }
        for (Map.Entry<String, Integer> entry : now.entrySet()) {
            WireNetworkManager.trySetSignalAt(level, pos, entry.getKey(), entry.getValue());
        }
        published = List.copyOf(now.keySet());
    }

    /**
     * One pass over the network's live module map, producing channel name -> analog value.
     *
     * <p>The merge is done here rather than through Dashpanels' helpers: {@code getCollectiveModules()}
     * is private, and the public {@code getCompiledModules()} is only a cache that
     * {@code compileModules()} refreshes -- so a module renamed in the panel GUI would still be
     * advertised under its old name until something happened to recompile. Merging the members
     * directly is always current, which is what makes a rename show up on the next tick.
     */
    private Map<String, Integer> sample() {
        Map<String, Integer> values = new LinkedHashMap<>();
        ModulesNetwork network = getOrCreate();
        if (network == null) {
            return values;
        }
        Map<String, Module> modules = new LinkedHashMap<>();
        for (ModulesNetworkMember member : network.members) {
            ModuleMap own = member.getModules();
            if (own == null) {
                continue;
            }
            for (Map.Entry<String, Module> entry : own.asEntryList()) {
                modules.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Module> entry : modules.entrySet()) {
            String name = entry.getKey();
            Module module = entry.getValue();
            List<String> subs = ModuleIOInfo.getMultiExtensionsIfAny(module);
            if (!subs.isEmpty()) {
                if (module instanceof IMultiInput multi) {
                    multi.getValues((sub, result) -> values.put(name + "." + sub, clamp(result.getAnalog())));
                }
                continue;
            }
            if (module instanceof IInput input) {
                values.put(name, clamp(input.getAnalog()));
            }
        }
        return values;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
