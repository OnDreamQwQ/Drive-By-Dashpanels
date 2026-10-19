package io.github.ondreamqwq.dbd;

import io.github.ondreamqwq.dbd.net.DbdNet;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Drive By Dashpanels.
 *
 * <p>A single block: the <b>Panel Link Center</b>. It joins a Dashpanels
 * {@code ModulesNetwork} the same way Dashpanels' own panel linker does, so it can see
 * every panel module on that network. Each tick it pushes every module's analog value
 * into the Drive By Wire wire network under the module's own name, which means the
 * module names are the wire channels.
 *
 * <p>The rename/removal switches live in {@link DbdConfig} (`config/drive_by_dashpanels-common.toml`).
 */
@Mod(DbdMod.MOD_ID)
public final class DbdMod {

    public static final String MOD_ID = "drive_by_dashpanels";

    public DbdMod(IEventBus modEventBus, ModContainer container) {
        DbdRegistry.register(modEventBus);
        modEventBus.addListener(DbdNet::register);
        container.registerConfig(ModConfig.Type.COMMON, DbdConfig.SPEC);
    }
}