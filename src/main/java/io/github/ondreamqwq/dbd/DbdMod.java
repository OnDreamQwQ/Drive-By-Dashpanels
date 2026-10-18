package io.github.ondreamqwq.dbd;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Drive By Dashpanels.
 *
 * <p>A single block: the <b>Panel Link Center</b>. It joins a Dashpanels
 * {@code ModulesNetwork} the same way Dashpanels' own panel linker does, so it can see
 * every panel module on that network. Each tick it pushes every module's analog value
 * into the Drive By Wire wire network under the module's own name, which means the
 * module names are the wire channels.
 */
@Mod(DbdMod.MOD_ID)
public final class DbdMod {

    public static final String MOD_ID = "drive_by_dashpanels";

    public DbdMod(IEventBus modEventBus) {
        DbdRegistry.register(modEventBus);
    }
}