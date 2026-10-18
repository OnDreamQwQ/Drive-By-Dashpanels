package io.github.ondreamqwq.dbd.content;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 *
 * <p>Renaming a module is handled here as well. Dashpanels' {@code ModuleMap#rename} only moves the
 * map key and keeps the same {@code Module} instance, so a rename is detectable by remembering which
 * name each instance was last seen under. A cable connection remembers the channel string it was
 * bound with, and that string is the OLD name after a rename, so the old name keeps being published
 * with the module's current value -- the connection survives the rename and never has to be remade.
 */
public class PanelLinkCenterBlockEntity extends ModulesNetworkMember {

    /** NBT key for the channel names inside the block-entity update tag. */
    public static final String TAG_CHANNELS = "dbd:channels";

    /** NBT key for the rename aliases, so a rename survives a reload. */
    public static final String TAG_ALIASES = "dbd:aliases";

    /** Values published on the previous tick, used to clear channels whose module went away. */
    private List<String> published = List.of();

    /** Channel names last pushed to clients, so the update tag is only resent when they change. */
    private List<String> synced = List.of();

    /**
     * Last name each module instance was seen under. {@code ModuleMap#rename} keeps the instance and
     * only moves the key, so a changed name for the same instance is exactly a rename.
     */
    private final Map<Module, String> seenNames = new IdentityHashMap<>();

    /** Old channel name -> the module name it now stands for. Persisted, so a rename outlives a reload. */
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public PanelLinkCenterBlockEntity(BlockPos pos, BlockState state) {
        super(DbdRegistry.PANEL_LINK_CENTER_BE.get(), pos, state);
    }

    /** The cable channels this center offers, one per module (and one per multi-input sub-key). */
    public List<String> channelNames() {
        return List.copyOf(valuesOf(collect()).keySet());
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
        Map<String, Module> modules = collect();
        trackRenames(modules);
        Map<String, Integer> now = valuesOf(modules);
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

    // ------------------------------------------------------------------ rename tracking

    /**
     * Spot renames by module identity, and keep the alias table following a chain of renames.
     *
     * <p>An alias whose module vanished is dropped: the channel is then cleared by {@link #publish}.
     */
    private void trackRenames(Map<String, Module> modules) {
        for (Map.Entry<String, Module> entry : modules.entrySet()) {
            String name = entry.getKey();
            String previous = seenNames.put(entry.getValue(), name);
            if (previous == null || previous.equals(name)) {
                continue;
            }
            // The instance kept its identity, only its name moved: every alias pointing at the old
            // name now points at the new one, and the old name itself becomes an alias.
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (alias.getValue().equals(previous)) {
                    alias.setValue(name);
                }
            }
            aliases.put(previous, name);
        }
        aliases.entrySet().removeIf(alias -> !modules.containsKey(alias.getValue()));
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
        aliases.clear();
        if (tag.contains(TAG_ALIASES, Tag.TAG_COMPOUND)) {
            CompoundTag stored = tag.getCompound(TAG_ALIASES);
            for (String oldName : stored.getAllKeys()) {
                aliases.put(oldName, stored.getString(oldName));
            }
        }
        if (level != null && level.isClientSide) {
            PanelLinkChannels.store(worldPosition, names);
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag stored = new CompoundTag();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            stored.putString(alias.getKey(), alias.getValue());
        }
        tag.put(TAG_ALIASES, stored);
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
        // Old channel names stay fed with the current value, which is what keeps a cable bound
        // before a rename working without the player touching it again.
        Map<String, Integer> outgoing = new LinkedHashMap<>(now);
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            Integer value = now.get(alias.getValue());
            if (value != null) {
                outgoing.putIfAbsent(alias.getKey(), value);
            }
        }
        for (String stale : published) {
            if (!outgoing.containsKey(stale)) {
                WireNetworkManager.trySetSignalAt(level, pos, stale, 0);
            }
        }
        for (Map.Entry<String, Integer> entry : outgoing.entrySet()) {
            WireNetworkManager.trySetSignalAt(level, pos, entry.getKey(), entry.getValue());
        }
        published = List.copyOf(outgoing.keySet());
    }

    /**
     * Merge every network member's live module map.
     *
     * <p>The merge is done here rather than through Dashpanels' helpers: {@code getCollectiveModules()}
     * is private, and the public {@code getCompiledModules()} is only a cache that
     * {@code compileModules()} refreshes -- so a module renamed in the panel GUI would still be
     * advertised under its old name until something happened to recompile. Merging the members
     * directly is always current, which is what makes a rename show up on the next tick.
     */
    private Map<String, Module> collect() {
        Map<String, Module> modules = new LinkedHashMap<>();
        ModulesNetwork network = getOrCreate();
        if (network == null) {
            return modules;
        }
        for (ModulesNetworkMember member : network.members) {
            ModuleMap own = member.getModules();
            if (own == null) {
                continue;
            }
            for (Map.Entry<String, Module> entry : own.asEntryList()) {
                modules.put(entry.getKey(), entry.getValue());
            }
        }
        return modules;
    }

    private static Map<String, Integer> valuesOf(Map<String, Module> modules) {
        Map<String, Integer> values = new LinkedHashMap<>();
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
