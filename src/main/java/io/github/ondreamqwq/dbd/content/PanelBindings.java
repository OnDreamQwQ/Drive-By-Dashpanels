package io.github.ondreamqwq.dbd.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.io.IOutput;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Wire &rarr; module bindings, keyed by the panel block entity itself.
 *
 * <p>Keying on the block entity instance (not on a position) means the server, the client and the
 * NBT round trip all use one lookup with no level or dimension bookkeeping: the entity already knows
 * where it is.
 *
 * <p>Both directions are many-to-many:
 *
 * <ul>
 *   <li>one source channel may feed several modules (splitting a cable);</li>
 *   <li>one module may listen to several source channels at once, in which case the effective value
 *       is the <b>largest</b> of them -- the same rule redstone uses when two wires meet at one
 *       torch.</li>
 * </ul>
 *
 * <p>The mapping is persisted in the panel's own block entity data, which also gives client sync for
 * free: the vanilla update tag carries it, so a player who comes into view immediately knows which
 * modules are bound and can draw their outlines.
 *
 * <h2>Why every value is immutable</h2>
 *
 * <p>{@link #bindings} is called from the render thread once per module <b>per frame</b>, while the
 * server thread mutates the table on player clicks. Copying on read would allocate a list per module
 * per frame; sharing a mutable list would risk a concurrent modification. So writes never touch a
 * published map: they build a new one, publish it, and every read is then just a map lookup with no
 * copy and no allocation.
 */
public final class PanelBindings {

    private static final String TAG_BINDINGS = "dbd:Bindings";
    private static final String TAG_MODULE = "module";

    /** Published snapshots: never mutated after {@code put}. */
    private static final Map<AbstractPanelBlockEntity, Map<String, List<PanelModuleBinding>>> STORE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Last value pushed into each module, so the output is only poked on change. */
    private static final Map<AbstractPanelBlockEntity, Map<String, Integer>> LAST =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Wire values per source position, valid for one server tick only.
     *
     * <p>Drive By Wire's {@code getSourceSignals} returns a fresh {@code Map.copyOf} on every call,
     * and one source usually feeds several modules, so without this cache a panel with four bound
     * modules on one cable would copy that map four times a tick.
     */
    private static final Map<Long, Map<String, Integer>> SIGNALS = new HashMap<>();

    /** The level {@link #SIGNALS} was filled from; signals never cross a dimension. */
    private static ServerLevel signalsLevel;

    private PanelBindings() {
    }

    /** The whole table for one panel. Immutable; safe to hold for the duration of a frame. */
    public static Map<String, List<PanelModuleBinding>> of(AbstractPanelBlockEntity panel) {
        Map<String, List<PanelModuleBinding>> bound = STORE.get(panel);
        return bound == null ? Map.of() : bound;
    }

    public static boolean isEmpty(AbstractPanelBlockEntity panel) {
        return of(panel).isEmpty();
    }

    /** Every source this module listens to. Immutable; no copy is made. */
    public static List<PanelModuleBinding> bindings(AbstractPanelBlockEntity panel, String module) {
        Map<String, List<PanelModuleBinding>> bound = STORE.get(panel);
        if (bound == null) {
            return List.of();
        }
        List<PanelModuleBinding> list = bound.get(module);
        return list == null ? List.of() : list;
    }

    /** First source of a module, or {@code null}. Prefer {@link #bindings} when all matter. */
    public static PanelModuleBinding get(AbstractPanelBlockEntity panel, String module) {
        List<PanelModuleBinding> all = bindings(panel, module);
        return all.isEmpty() ? null : all.get(0);
    }

    public static boolean has(AbstractPanelBlockEntity panel, String module, BlockPos source, String channel) {
        for (PanelModuleBinding binding : bindings(panel, module)) {
            if (binding.source().equals(source) && binding.channel().equals(channel)) {
                return true;
            }
        }
        return false;
    }

    /** Adds one source to a module. Returns {@code false} if that pair was already there. */
    public static boolean put(AbstractPanelBlockEntity panel, String module, PanelModuleBinding binding) {
        synchronized (STORE) {
            Map<String, List<PanelModuleBinding>> current = STORE.get(panel);
            List<PanelModuleBinding> list = current == null ? null : current.get(module);
            if (list != null) {
                for (PanelModuleBinding existing : list) {
                    if (existing.source().equals(binding.source()) && existing.channel().equals(binding.channel())) {
                        return false;
                    }
                }
            }
            List<PanelModuleBinding> grown = new ArrayList<>(list == null ? List.of() : list);
            grown.add(binding);
            Map<String, List<PanelModuleBinding>> next =
                    current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
            next.put(module, List.copyOf(grown));
            publish(panel, next);
            return true;
        }
    }

    /** Drops one source from a module, leaving its other sources alone. */
    public static boolean removeBinding(AbstractPanelBlockEntity panel, String module, BlockPos source, String channel) {
        synchronized (STORE) {
            Map<String, List<PanelModuleBinding>> current = STORE.get(panel);
            if (current == null) {
                return false;
            }
            List<PanelModuleBinding> list = current.get(module);
            if (list == null) {
                return false;
            }
            List<PanelModuleBinding> left = new ArrayList<>(list.size());
            for (PanelModuleBinding binding : list) {
                if (!(binding.source().equals(source) && binding.channel().equals(channel))) {
                    left.add(binding);
                }
            }
            if (left.size() == list.size()) {
                return false;
            }
            Map<String, List<PanelModuleBinding>> next = new LinkedHashMap<>(current);
            if (left.isEmpty()) {
                next.remove(module);
            } else {
                next.put(module, List.copyOf(left));
            }
            publish(panel, next);
            return true;
        }
    }

    /** Drops every source of one module. */
    public static boolean remove(AbstractPanelBlockEntity panel, String module) {
        synchronized (STORE) {
            Map<String, List<PanelModuleBinding>> current = STORE.get(panel);
            if (current == null || !current.containsKey(module)) {
                return false;
            }
            Map<String, List<PanelModuleBinding>> next = new LinkedHashMap<>(current);
            next.remove(module);
            publish(panel, next);
            return true;
        }
    }

    /** Replace the published snapshot; an empty table drops the entry entirely. */
    private static void publish(AbstractPanelBlockEntity panel, Map<String, List<PanelModuleBinding>> next) {
        if (next.isEmpty()) {
            STORE.remove(panel);
        } else {
            STORE.put(panel, Collections.unmodifiableMap(next));
        }
    }

    public static void forget(AbstractPanelBlockEntity panel) {
        STORE.remove(panel);
        LAST.remove(panel);
    }

    public static Set<AbstractPanelBlockEntity> panels() {
        synchronized (STORE) {
            return Set.copyOf(STORE.keySet());
        }
    }

    // ---------------------------------------------------------------- persistence

    public static void write(AbstractPanelBlockEntity panel, CompoundTag tag) {
        Map<String, List<PanelModuleBinding>> bound = of(panel);
        if (bound.isEmpty()) {
            tag.remove(TAG_BINDINGS);
            return;
        }
        ListTag list = new ListTag();
        for (Map.Entry<String, List<PanelModuleBinding>> entry : bound.entrySet()) {
            for (PanelModuleBinding binding : entry.getValue()) {
                CompoundTag element = binding.save();
                element.putString(TAG_MODULE, entry.getKey());
                list.add(element);
            }
        }
        tag.put(TAG_BINDINGS, list);
    }

    public static void read(AbstractPanelBlockEntity panel, CompoundTag tag) {
        if (!tag.contains(TAG_BINDINGS, Tag.TAG_LIST)) {
            forget(panel);
            return;
        }
        ListTag list = tag.getList(TAG_BINDINGS, Tag.TAG_COMPOUND);
        Map<String, List<PanelModuleBinding>> bound = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag element = list.getCompound(i);
            String module = element.getString(TAG_MODULE);
            if (module.isEmpty()) {
                continue;
            }
            PanelModuleBinding binding = PanelModuleBinding.load(element);
            List<PanelModuleBinding> sources = bound.computeIfAbsent(module, key -> new ArrayList<>());
            boolean duplicate = false;
            for (PanelModuleBinding existing : sources) {
                if (existing.source().equals(binding.source()) && existing.channel().equals(binding.channel())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                sources.add(binding);
            }
        }
        Map<String, List<PanelModuleBinding>> next = new LinkedHashMap<>();
        for (Map.Entry<String, List<PanelModuleBinding>> entry : bound.entrySet()) {
            next.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        publish(panel, next);
    }

    // -------------------------------------------------------------------- delivery

    /** Server tick: push the effective wire value into every bound module. */
    public static void deliver() {
        SIGNALS.clear();
        signalsLevel = null;
        List<AbstractPanelBlockEntity> snapshot = new ArrayList<>(panels());
        for (AbstractPanelBlockEntity panel : snapshot) {
            if (panel.isRemoved()) {
                forget(panel);
                continue;
            }
            Level level = panel.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                continue;
            }
            Map<String, List<PanelModuleBinding>> bound = of(panel);
            if (bound.isEmpty()) {
                continue;
            }
            boolean dirty = false;
            for (Map.Entry<String, List<PanelModuleBinding>> entry : bound.entrySet()) {
                String moduleName = entry.getKey();
                Module module = panel.getModule(moduleName);
                if (!(module instanceof IOutput output)) {
                    // Module removed, renamed or simply unable to receive: drop it.
                    if (remove(panel, moduleName)) {
                        dirty = true;
                    }
                    continue;
                }
                int best = 0;
                boolean live = false;
                boolean pruned = false;
                for (PanelModuleBinding binding : entry.getValue()) {
                    if (!WireNetworkManager.hasConnection(serverLevel, binding.source(), binding.sink(),
                            binding.face(), binding.channel())) {
                        // This cable is gone (cut, replaced or the source removed).
                        if (removeBinding(panel, moduleName, binding.source(), binding.channel())) {
                            pruned = true;
                            dirty = true;
                        }
                        continue;
                    }
                    live = true;
                    best = Math.max(best, valueOf(serverLevel, binding));
                }
                if (live) {
                    apply(panel, moduleName, output, best);
                } else if (pruned) {
                    apply(panel, moduleName, output, 0);
                }
            }
            if (dirty) {
                markDirty(panel);
            }
        }
    }

    /** Call after any binding change so nearby clients redraw the outlines. */
    public static void markDirty(AbstractPanelBlockEntity panel) {
        Level level = panel.getLevel();
        if (level == null || level.isClientSide) {
            panel.setChanged();
            return;
        }
        panel.setChanged();
        // BaseBlockEntity#blockChanged pushes the update tag to everyone tracking the chunk.
        panel.blockChanged();
    }

    /** Drop every source of a module and stop it from holding the last value. */
    public static boolean unbind(AbstractPanelBlockEntity panel, String moduleName) {
        Module module = panel.getModule(moduleName);
        if (module instanceof IOutput output) {
            apply(panel, moduleName, output, 0);
        }
        boolean removed = remove(panel, moduleName);
        if (removed) {
            markDirty(panel);
        }
        return removed;
    }

    /** Every module on this panel that is fed by the given source channel. */
    public static List<String> boundTo(AbstractPanelBlockEntity panel, BlockPos source, String channel) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<PanelModuleBinding>> entry : of(panel).entrySet()) {
            for (PanelModuleBinding binding : entry.getValue()) {
                if (binding.source().equals(source) && binding.channel().equals(channel)) {
                    out.add(entry.getKey());
                    break;
                }
            }
        }
        return out;
    }

    /** Is anything on this panel still fed by the given source channel? */
    public static boolean uses(AbstractPanelBlockEntity panel, BlockPos source, String channel) {
        return !boundTo(panel, source, channel).isEmpty();
    }

    /**
     * Drop one source channel from every module it feeds, leaving other sources of
     * those modules intact.
     *
     * <p>This is the only way to really get rid of a cable: a cable can feed several
     * modules at once, so cutting it has to mean "release all of them".
     */
    public static int unbindChannel(AbstractPanelBlockEntity panel, BlockPos source, String channel) {
        int dropped = 0;
        for (String moduleName : boundTo(panel, source, channel)) {
            if (removeBinding(panel, moduleName, source, channel)) {
                dropped++;
                if (bindings(panel, moduleName).isEmpty()) {
                    Module module = panel.getModule(moduleName);
                    if (module instanceof IOutput output) {
                        apply(panel, moduleName, output, 0);
                    }
                }
            }
        }
        if (dropped > 0) {
            markDirty(panel);
        }
        return dropped;
    }

    private static void apply(AbstractPanelBlockEntity panel, String moduleName, IOutput output, int value) {
        Map<String, Integer> last;
        synchronized (LAST) {
            last = LAST.computeIfAbsent(panel, key -> new LinkedHashMap<>());
            Integer previous = last.get(moduleName);
            if (previous != null && previous == value) {
                return;
            }
            last.put(moduleName, value);
        }
        output.setAnalog(value);
        panel.networkUpdate(panel.getOrCreate());
    }

    private static int valueOf(ServerLevel level, PanelModuleBinding binding) {
        Map<String, Integer> signals = signals(level, binding.source());
        Integer raw = signals.get(binding.channel());
        if (raw == null) {
            if (WireNetworkManager.WORLD_CHANNEL.equals(binding.channel())) {
                raw = WireNetworkManager.computeWorldSignal(level, binding.source());
            } else {
                raw = 0;
            }
        }
        return Mth.clamp(raw, 0, 15);
    }

    /** This tick's wire values for one source, fetched at most once per tick. */
    private static Map<String, Integer> signals(ServerLevel level, BlockPos source) {
        if (level != signalsLevel) {
            SIGNALS.clear();
            signalsLevel = level;
        }
        long key = source.asLong();
        Map<String, Integer> cached = SIGNALS.get(key);
        if (cached == null) {
            WireNetworkManager manager = WireNetworkManager.get(level);
            cached = manager == null ? Map.of() : manager.getSourceSignals(source);
            SIGNALS.put(key, cached);
        }
        return cached;
    }

    /** Forget a module's cached value, e.g. after its binding changed. */
    public static void resetValue(AbstractPanelBlockEntity panel, String moduleName) {
        synchronized (LAST) {
            Map<String, Integer> last = LAST.get(panel);
            if (last != null) {
                last.remove(moduleName);
            }
        }
    }

    /** Utility for callers that only have a position. */
    public static AbstractPanelBlockEntity panelAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AbstractPanelBlockEntity panel ? panel : null;
    }
}
