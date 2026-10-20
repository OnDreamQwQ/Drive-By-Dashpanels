package io.github.ondreamqwq.dbd.client;

import io.github.ondreamqwq.dbd.DbdConfig;

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
 *   <li>the selected source feeds this module on the channel scrolled to -> yellow;</li>
 *   <li>the selected source feeds this module on another channel -> grey;</li>
 *   <li>the selected source has nothing to do with this module -> no outline.</li>
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
    private static Field channelField;
    private static boolean channelResolved;

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
     * Move the cable onto {@code channel}, as if the player had scrolled to it.
     *
     * <p>{@code currentChannel} is private static with a getter and no setter, and it is pure client
     * state: the channel only reaches the server inside the "add connection" packet the next time the
     * player clicks a sink. Writing it here is therefore exactly what scrolling does, with no packet
     * and no server round trip.
     *
     * @return whether the write went through
     */
    public static boolean select(String channel) {
        if (channel == null || channel.isEmpty()) {
            return false;
        }
        Field field = channelField();
        if (field == null) {
            return false;
        }
        try {
            field.set(null, channel);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field channelField() {
        if (!channelResolved) {
            channelResolved = true;
            try {
                Field field = ClientWireNetworkHandler.class.getDeclaredField("currentChannel");
                field.setAccessible(true);
                channelField = field;
            } catch (Throwable ignored) {
                channelField = null;
            }
        }
        return channelField;
    }

    /**
     * Outline colour for one module, or {@code 0} when nothing should be drawn.
     *
     * <p>A module may listen to several sources. It is yellow when <i>any</i> of them is the source
     * and channel currently selected, and grey only when the selected source is one of its sources
     * but on another channel -- a source the module has nothing to do with draws no outline at all,
     * so a cable picked up elsewhere never lights up unrelated modules.
     */
    public static int frameColor(AbstractPanelBlockEntity panel, Module module) {
        if (!DbdConfig.showConnectionFrames()) {
            return 0;
        }
        // Checked before the binding lookup: this runs once per module per frame, and with no cable
        // in hand (the normal case) the answer is "draw nothing" without touching the table at all.
        BlockPos source = source();
        if (source == null) {
            return 0;
        }
        List<PanelModuleBinding> bindings = PanelBindings.bindings(panel, module.getName());
        if (bindings.isEmpty()) {
            return 0;
        }
        String channel = channel();
        boolean sameSource = false;
        for (PanelModuleBinding binding : bindings) {
            if (!source.equals(binding.source())) {
                continue;
            }
            sameSource = true;
            if (channel != null && channel.equals(binding.channel())) {
                return MATCHING;
            }
        }
        return sameSource ? OTHER : 0;
    }
}
