package io.github.ondreamqwq.dbd.client;

import java.lang.reflect.Field;
import java.util.List;

import edn.stratodonut.drivebywire.client.ClientWireNetworkHandler;
import io.github.ondreamqwq.dbd.content.PanelBindings;
import io.github.ondreamqwq.dbd.content.PanelModuleBinding;
import moth.boxxed.panels.api.module.Module;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.BlockPos;

/**
 * Client-side view of what the player currently has selected in Drive By Wire.
 *
 * <p>Three states drive the outline around a wired module:
 *
 * <ul>
 *   <li>no source selected -> no outline at all;</li>
 *   <li>source selected, module on that source and on the currently scrolled-to channel -> yellow;</li>
 *   <li>source selected, anything else -> grey.</li>
 * </ul>
 *
 * <p>{@code ClientWireNetworkHandler#getCurrentChannel()} is public, but {@code selectedSource} is a
 * private static field and there is no getter. Reflecting on it once is cheaper and far less
 * fragile than shadowing private client state from a mixin that would then have to guess when it
 * changes: "is a source selected" has to be true at paint time, not merely when a scroll event
 * last fired.
 */
public final class WireSelection {

    /** The module sits on the source + channel the player is looking at. */
    public static final int MATCHING = 0xFFFFC400;

    /** The module is wired, but to another source or another channel. */
    public static final int OTHER = 0xFF8C8C8C;

    private static Field sourceField;
    private static boolean resolved;

    private WireSelection() {}

    private static Field sourceField() {
        if (!resolved) {
            resolved = true;
            try {
                Field field = ClientWireNetworkHandler.class.getDeclaredField("selectedSource");
                field.setAccessible(true);
                sourceField = field;
            } catch (Throwable ignored) {
                sourceField = null;
            }
        }
        return sourceField;
    }

    /** The source block the player selected, or {@code null} when nothing is selected. */
    public static BlockPos source() {
        Field field = sourceField();
        if (field == null) {
            return null;
        }
        try {
            return (BlockPos) field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The channel currently scrolled to, or {@code null} if DBW does not answer. */
    public static String channel() {
        try {
            return ClientWireNetworkHandler.getCurrentChannel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Outline colour for one module, or {@code 0} when nothing should be drawn.
     *
     * <p>A module may listen to several sources, so it is yellow when <i>any</i> of
     * them is the source and channel currently selected.
     */
    public static int frameColor(AbstractPanelBlockEntity panel, Module module) {
        List<PanelModuleBinding> bindings = PanelBindings.bindings(panel, module.getName());
        if (bindings.isEmpty()) {
            return 0;
        }
        BlockPos source = source();
        if (source == null) {
            return 0;
        }
        String channel = channel();
        if (channel != null) {
            for (PanelModuleBinding binding : bindings) {
                if (source.equals(binding.source()) && channel.equals(binding.channel())) {
                    return MATCHING;
                }
            }
        }
        return OTHER;
    }
}
