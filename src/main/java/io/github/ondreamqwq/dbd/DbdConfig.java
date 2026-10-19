package io.github.ondreamqwq.dbd;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The behaviour switches for {@code 面板链接中心}, written to {@code config/drive_by_dashpanels-common.toml}.
 *
 * <p>Both defaults are chosen so that nothing a player built is ever thrown away silently: a rename
 * carries the existing cable over to the new name, but a module that simply goes missing leaves its
 * cable alone until someone asks for the cleanup.
 */
public final class DbdConfig {

    public static final ModConfigSpec SPEC;

    /**
     * A cable remembers the channel name it was bound with. With this on, renaming a module rewrites
     * the cable's channel to the new name, so the channel list stays clean. With it off, the old
     * name is kept alive as an alias instead: the cable keeps working and the old name stays
     * selectable, but the channel list grows by one entry per rename.
     */
    public static final ModConfigSpec.BooleanValue MIGRATE_CONNECTIONS_ON_RENAME;

    /**
     * A module that disappears (removed from the panel) leaves its cable links behind, and they sit
     * on channel 0 forever. With this on those links are deleted the moment the module goes away.
     *
     * <p>Off by default on purpose: while a chunk holding the panels is unloaded the network looks
     * empty for a tick, and deleting then would wipe real wiring.
     */
    public static final ModConfigSpec.BooleanValue REMOVE_CONNECTIONS_ON_MODULE_LOSS;

    /**
     * How far the panel remote's camera leans off the panel's working face, in degrees.
     *
     * <p>Positive values look down at the panel, negative values look up at it. Zero is a straight
     * face-on view. A config value rather than a constant so the sign can be flipped without another
     * build.
     */
    public static final ModConfigSpec.DoubleValue CAMERA_TILT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // 改名后把线缆连接迁移到新名字。关掉的话旧名字会作为别名保留（线缆照样能用，但频道表
        // 每改名一次就多一条）。默认开。
        MIGRATE_CONNECTIONS_ON_RENAME = builder
                .comment("Rename a module and the cables bound to it are moved to the new name.",
                        "When false the old name is kept as an alias instead: the cable keeps working,",
                        "the old name stays selectable, but the channel list grows by one per rename.")
                .define("migrateConnectionsOnRename", true);

        // 模块被拆掉时自动删除它留下的线缆连接。默认关：面板所在区块卸载时网络会短暂变空，
        // 自动删除会把玩家真实的接线删掉。
        REMOVE_CONNECTIONS_ON_MODULE_LOSS = builder
                .comment("Delete a module's cable links as soon as the module is removed from the panel.",
                        "Off by default: while a chunk holding the panels is unloaded the network looks",
                        "empty for a tick, and deleting then would wipe real wiring. Sneak-right-click",
                        "the panel link center to clean up manually instead.")
                .define("removeConnectionsOnModuleLoss", false);

        CAMERA_TILT = builder
                .comment("Degrees the panel remote's camera leans off the panel face.",
                        "Positive looks down at the panel, negative looks up at it, 0 is face on.")
                .defineInRange("cameraTilt", 45.0D, -89.0D, 89.0D);

        SPEC = builder.build();
    }

    private DbdConfig() {
    }

    public static boolean migrateOnRename() {
        return MIGRATE_CONNECTIONS_ON_RENAME.get();
    }

    public static boolean removeOnModuleLoss() {
        return REMOVE_CONNECTIONS_ON_MODULE_LOSS.get();
    }

    public static float cameraTilt() {
        return CAMERA_TILT.get().floatValue();
    }
}
