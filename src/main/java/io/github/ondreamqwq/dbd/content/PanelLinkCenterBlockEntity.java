package io.github.ondreamqwq.dbd.content;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import edn.stratodonut.drivebywire.wire.graph.WireNetworkNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import io.github.ondreamqwq.dbd.DbdRegistry;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.ModuleMap;
import moth.boxxed.panels.api.module.io.IInput;
import moth.boxxed.panels.api.module.io.IMultiInput;
import moth.boxxed.panels.api.module.io.ModuleIOInfo;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * bound with, and that string is the OLD name after a rename; the old name therefore keeps being
 * published with the module's current value AND is offered back to the client as a selectable
 * channel. Both halves matter: the first keeps an existing cable alive, the second keeps the channel
 * list scrollable afterwards -- without it the wire would sit on a name that no longer appears in
 * the list and {@code wire$nextChannel} would have no index to walk from.
 */
public class PanelLinkCenterBlockEntity extends ModulesNetworkMember {

    /** NBT key for the channel names inside the block-entity update tag. */
    public static final String TAG_CHANNELS = "dbd:channels";

    /** NBT key for the rename aliases, so a rename survives a reload. */
    public static final String TAG_ALIASES = "dbd:aliases";

    /**
     * Upper bound on remembered rename aliases. A rename is never pruned just because its module is
     * gone -- a module torn down and put back under its new name has to revive the old cable without
     * a re-bind -- so the table is capped by age instead of by liveness.
     */
    private static final int MAX_ALIASES = 128;

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

    /**
     * The cable channels this center offers: one per module (one per multi-input sub-key), plus the
     * old name of every renamed module so a cable bound before the rename stays selectable.
     */
    public List<String> channelNames() {
        return channelList(valuesOf(collect()).keySet());
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
        List<String> names = channelList(now.keySet());
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
            BlockPos pos = getBlockPos();
            if (WireNetworkManager.get(level) != null) {
                for (String channel : published) {
                    WireNetworkManager.trySetSignalAt(level, pos, channel, 0);
                }
                // The block is gone, so every cable that left it is garbage. Drive By Wire keeps
                // those links in its own state with no "drop everything from this source" call, so
                // they are dropped one sink at a time.
                removeConnections(level, pos, channel -> true);
            }
            published = List.of();
        }
        super.remove();
    }

    // ------------------------------------------------------------------ rename tracking

    /**
     * Spot renames by module identity, and keep the alias table following a chain of renames.
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
        // A module that disappears cannot feed its old channel any more, so publish() writes 0 to it
        // and the cable goes quiet. The alias record is deliberately KEPT: tearing a module down and
        // later putting a new one back under the renamed target has to revive the cable without a
        // re-bind, and pruning here was exactly what broke that. Only the instance bookkeeping is
        // dropped, so a destroyed module does not linger in memory.
        IdentityHashMap<Module, Boolean> alive = new IdentityHashMap<>();
        for (Module module : modules.values()) {
            alive.put(module, Boolean.TRUE);
        }
        seenNames.keySet().retainAll(alive.keySet());
        trimAliases();
    }

    /** Cap the alias table by age; entries are insertion ordered, so the oldest go first. */
    private void trimAliases() {
        Iterator<String> oldest = aliases.keySet().iterator();
        while (aliases.size() > MAX_ALIASES && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** Forget every rename alias. Returns how many were dropped. */
    public int clearAliases() {
        int count = aliases.size();
        aliases.clear();
        return count;
    }

    /**
     * Drop the cable links of channels that no module feeds any more. Called when the player asks
     * for it explicitly: doing this automatically would delete real wiring every time a chunk with
     * the panels unloads and the network looks momentarily empty.
     *
     * @return how many links were removed
     */
    public int dropDanglingConnections() {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return 0;
        }
        Map<String, Integer> live = valuesOf(collect());
        int[] removed = {0};
        removeConnections(level, getBlockPos(), channel -> !live.containsKey(channel) && !feeds(live, channel), removed);
        synced = List.of();
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        return removed[0];
    }

    /** Whether {@code channel} is still fed, either directly or through a rename alias. */
    private boolean feeds(Map<String, Integer> live, String channel) {
        String target = aliases.get(channel);
        return target != null && live.containsKey(target);
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
     * Live channel names first, then any renamed-away name, so a cable that was bound before a
     * rename still finds itself in the list that {@code wire$nextChannel} walks.
     */
    private List<String> channelList(Collection<String> live) {
        List<String> names = new ArrayList<>(live);
        for (String alias : aliases.keySet()) {
            if (!names.contains(alias)) {
                names.add(alias);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Delete the cable links of this source whose channel passes {@code filter}.
     *
     * <p>Drive By Wire records a link as (source position, channel) -> sinks, and only exposes a
     * per-link removal, so the sinks have to be read back out of {@code getNetwork()} first.
     */
    private void removeConnections(Level level, BlockPos source, Predicate<String> filter) {
        removeConnections(level, source, filter, new int[1]);
    }

    private void removeConnections(Level level, BlockPos source, Predicate<String> filter, int[] removed) {
        WireNetworkManager network = WireNetworkManager.get(level);
        if (network == null) {
            return;
        }
        Map<String, Set<WireNetworkNode.WireNetworkSink>> perChannel = network.getNetwork().get(source.asLong());
        if (perChannel == null) {
            return;
        }
        for (Map.Entry<String, Set<WireNetworkNode.WireNetworkSink>> entry : perChannel.entrySet()) {
            if (!filter.test(entry.getKey())) {
                continue;
            }
            for (WireNetworkNode.WireNetworkSink sink : List.copyOf(entry.getValue())) {
                int facing = sink.direction();
                if (facing < 0 || facing >= Direction.values().length) {
                    continue;
                }
                if (WireNetworkManager.removeConnection(
                        level, source, BlockPos.of(sink.position()),
                        Direction.from3DDataValue(facing), entry.getKey())) {
                    removed[0]++;
                }
            }
        }
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
