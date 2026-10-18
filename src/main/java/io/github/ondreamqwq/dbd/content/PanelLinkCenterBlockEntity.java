package io.github.ondreamqwq.dbd.content;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.ondreamqwq.dbd.DbdRegistry;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.module.ModuleMap;
import moth.boxxed.panels.api.module.io.IMultiInput;
import moth.boxxed.panels.api.module.io.IInput;
import moth.boxxed.panels.api.module.io.ModuleIOInfo;
import moth.boxxed.panels.api.network.ModulesNetwork;
import moth.boxxed.panels.api.network.ModulesNetworkMember;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Joins the Dashpanels {@code ModulesNetwork} exactly like Dashpanels' own panel linker, so it can
 * read the compiled module map of every panel on the same cable network.
 *
 * <p>Every tick it publishes each module's analog value (0-15) into the Drive By Wire wire network
 * under the module's own name, so the module names become the cable channels. A channel that
 * disappears is written back to 0 once, so a removed module does not leave a stuck signal behind.
 */
public class PanelLinkCenterBlockEntity extends ModulesNetworkMember {

    /** Values published on the previous tick, used to clear channels whose module went away. */
    private List<String> published = List.of();

    public PanelLinkCenterBlockEntity(BlockPos pos, BlockState state) {
        super(DbdRegistry.PANEL_LINK_CENTER_BE.get(), pos, state);
    }

    /** The channel names this center offers, one per module (and one per multi-input sub-key). */
    public List<String> channelNames() {
        ModuleMap modules = modules();
        if (modules == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Module> entry : modules.asEntryList()) {
            List<String> subs = ModuleIOInfo.getMultiExtensionsIfAny(entry.getValue());
            if (subs.isEmpty()) {
                names.add(entry.getKey());
            } else {
                for (String sub : subs) {
                    names.add(entry.getKey() + "." + sub);
                }
            }
        }
        return names;
    }

    @Override
    public void tick(Level level, BlockPos pos, BlockState state) {
        super.tick(level, pos, state);
        if (level.isClientSide) {
            return;
        }
        publish(level, pos);
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

    private void publish(Level level, BlockPos pos) {
        WireNetworkManager network = WireNetworkManager.get(level);
        if (network == null) {
            return;
        }
        Map<String, Integer> now = sample();
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

    private Map<String, Integer> sample() {
        Map<String, Integer> values = new LinkedHashMap<>();
        ModuleMap modules = modules();
        if (modules == null) {
            return values;
        }
        for (Map.Entry<String, Module> entry : modules.asEntryList()) {
            String name = entry.getKey();
            Module module = entry.getValue();
            if (module instanceof IMultiInput multi) {
                multi.getValues((sub, result) -> values.put(name + "." + sub, clamp(result.getAnalog())));
            } else if (module instanceof IInput input) {
                values.put(name, clamp(input.getAnalog()));
            }
        }
        return values;
    }

    private ModuleMap modules() {
        ModulesNetwork network = getOrCreate();
        return network == null ? null : network.getCompiledModules();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}