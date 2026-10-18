package io.github.ondreamqwq.dbd;

import io.github.ondreamqwq.dbd.content.PanelLinkCenterBlock;
import io.github.ondreamqwq.dbd.content.PanelLinkCenterBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DbdRegistry {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DbdMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DbdMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DbdMod.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DbdMod.MOD_ID);

    public static final DeferredBlock<PanelLinkCenterBlock> PANEL_LINK_CENTER = BLOCKS.register(
            "panel_link_center",
            () -> new PanelLinkCenterBlock(BlockBehaviour.Properties.of()
                    .strength(2.0F, 6.0F)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> PANEL_LINK_CENTER_ITEM = ITEMS.register(
            "panel_link_center",
            () -> new BlockItem(PANEL_LINK_CENTER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PanelLinkCenterBlockEntity>>
            PANEL_LINK_CENTER_BE = BLOCK_ENTITIES.register("panel_link_center", () ->
                    BlockEntityType.Builder
                            .of(PanelLinkCenterBlockEntity::new, PANEL_LINK_CENTER.get())
                            .build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + DbdMod.MOD_ID))
                    .icon(() -> new ItemStack(PANEL_LINK_CENTER_ITEM.get()))
                    .displayItems((params, output) -> output.accept(PANEL_LINK_CENTER_ITEM.get()))
                    .build());

    private DbdRegistry() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
    }


}