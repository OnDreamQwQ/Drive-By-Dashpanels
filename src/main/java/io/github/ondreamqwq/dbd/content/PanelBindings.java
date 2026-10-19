package io.github.ondreamqwq.dbd.content;

import java.util.ArrayList;
import java.util.Collections;
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
 * <p>Keying on the block entity instance (not on a position) means the server,
 * the client and the NBT round trip all use one lookup with no level or
 * dimension bookkeeping: the entity already knows where it is.
 *
 * <p>The mapping is persisted in the panel's own block entity data, which also
 * gives client sync for free: the vanilla update tag carries it, so a player who
 * comes into view immediately knows which modules are bound and can draw their
 * outlines.
 */
public final class PanelBindings {

    private static final String TAG_BINDINGS = "dbd:Bindings";
    private static final String TAG_MODULE = "module";

    private static final Map<AbstractPanelBlockEntity, Map<String, PanelModuleBinding>> STORE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Last value pushed into each module, so the output is only poked on change. */
    private static final Map<AbstractPanelBlockEntity, Map<String, Integer>> LAST =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PanelBindings() {
    }

    public static Map<String, PanelModuleBinding> of(AbstractPanelBlockEntity panel) {
        synchronized (STORE) {
            Map<String, PanelModuleBinding> bound = STORE.get(panel);
            return bound == null || bound.isEmpty() ? Map.of() : Map.copyOf(bound);
        }
    }

    public static boolean isEmpty(AbstractPanelBlockEntity panel) {
        return of(panel).isEmpty();
    }

    public static PanelModuleBinding get(AbstractPanelBlockEntity panel, String module) {
        synchronized (STORE) {
            Map<String, PanelModuleBinding> bound = STORE.get(panel);
            return bound == null ? null : bound.get(module);
        }
    }

    public static void put(AbstractPanelBlockEntity panel, String module, PanelModuleBinding binding) {
        synchronized (STORE) {
            STORE.computeIfAbsent(panel, key -> new LinkedHashMap<>()).put(module, binding);
        }
    }

    public static PanelModuleBinding remove(AbstractPanelBlockEntity panel, String module) {
        synchronized (STORE) {
            Map<String, PanelModuleBinding> bound = STORE.get(panel);
            if (bound == null) {
                return null;
            }
            PanelModuleBinding old = bound.remove(module);
            if (bound.isEmpty()) {
                STORE.remove(panel);
            }
            return old;
        }
    }

    public static void forget(AbstractPanelBlockEntity panel) {
        synchronized (STORE) {
            STORE.remove(panel);
        }
        LAST.remove(panel);
    }

    public static Set<AbstractPanelBlockEntity> panels() {
        synchronized (STORE) {
            return Set.copyOf(STORE.keySet());
        }
    }

    // ---------------------------------------------------------------- persistence

    public static void write(AbstractPanelBlockEntity panel, CompoundTag tag) {
        Map<String, PanelModuleBinding> bound = of(panel);
        if (bound.isEmpty()) {
            tag.remove(TAG_BINDINGS);
            return;
        }
        ListTag list = new ListTag();
        for (Map.Entry<String, PanelModuleBinding> entry : bound.entrySet()) {
            CompoundTag element = entry.getValue().save();
            element.putString(TAG_MODULE, entry.getKey());
            list.add(element);
        }
        tag.put(TAG_BINDINGS, list);
    }

    public static void read(AbstractPanelBlockEntity panel, CompoundTag tag) {
        if (!tag.contains(TAG_BINDINGS, Tag.TAG_LIST)) {
            forget(panel);
            return;
        }
        ListTag list = tag.getList(TAG_BINDINGS, Tag.TAG_COMPOUND);
        Map<String, PanelModuleBinding> bound = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag element = list.getCompound(i);
            String module = element.getString(TAG_MODULE);
            if (!module.isEmpty()) {
                bound.put(module, PanelModuleBinding.load(element));
            }
        }
        synchronized (STORE) {
            if (bound.isEmpty()) {
                STORE.remove(panel);
            } else {
                STORE.put(panel, bound);
            }
        }
    }

    // -------------------------------------------------------------------- delivery

    /** Server tick: push each bound wire value into its module. */
    public static void deliver() {
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
            Map<String, PanelModuleBinding> bound = of(panel);
            if (bound.isEmpty()) {
                continue;
            }
            boolean dropped = false;
            for (Map.Entry<String, PanelModuleBinding> entry : bound.entrySet()) {
                String moduleName = entry.getKey();
                PanelModuleBinding binding = entry.getValue();
                Module module = panel.getModule(moduleName);
                if (!(module instanceof IOutput output)) {
                    // Module removed, renamed or simply unable to receive: drop it.
                    remove(panel, moduleName);
                    dropped = true;
                    continue;
                }
                if (!WireNetworkManager.hasConnection(serverLevel, binding.source(), binding.sink(),
                        binding.face(), binding.channel())) {
                    // The cable is gone (cut, replaced or the source removed).
                    apply(panel, moduleName, output, 0);
                    remove(panel, moduleName);
                    dropped = true;
                    continue;
                }
                apply(panel, moduleName, output, valueOf(serverLevel, binding));
            }
            if (dropped) {
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

    /** Drop a binding and stop the module from holding the last value. */
    public static boolean unbind(AbstractPanelBlockEntity panel, String moduleName) {
        Module module = panel.getModule(moduleName);
        if (module instanceof IOutput output) {
            apply(panel, moduleName, output, 0);
        }
        boolean removed = remove(panel, moduleName) != null;
        if (removed) {
            markDirty(panel);
        }
        return removed;
    }

    private static void apply(AbstractPanelBlockEntity panel, String moduleName, IOutput output, int value) {
        Map<String, Integer> last;
        synchronized (LAST) {
            last = LAST.computeIfAbsent(panel, key -> new LinkedHashMap<>());
        }
        Integer previous = last.get(moduleName);
        if (previous != null && previous == value) {
            return;
        }
        last.put(moduleName, value);
        output.setAnalog(value);
        panel.networkUpdate(panel.getOrCreate());
    }

    private static int valueOf(ServerLevel level, PanelModuleBinding binding) {
        WireNetworkManager manager = WireNetworkManager.get(level);
        Integer raw = manager.getSourceSignals(binding.source()).get(binding.channel());
        if (raw == null) {
            if (WireNetworkManager.WORLD_CHANNEL.equals(binding.channel())) {
                raw = WireNetworkManager.computeWorldSignal(level, binding.source());
            } else {
                raw = 0;
            }
        }
        return Mth.clamp(raw, 0, 15);
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
