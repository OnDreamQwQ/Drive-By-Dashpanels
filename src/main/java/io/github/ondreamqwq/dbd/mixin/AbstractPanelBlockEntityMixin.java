package io.github.ondreamqwq.dbd.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.ondreamqwq.dbd.content.PanelBindings;
import moth.boxxed.panels.api.panel.AbstractPanelBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Stores the wire bindings in the panel's own block entity data.
 *
 * <p>This gets client sync for free: Dashpanels' {@code BaseBlockEntity} builds its update tag by
 * calling {@code saveAdditional} and applies it by calling {@code loadAdditional}, so anything
 * written there reaches every player who can see the panel. That is what lets the outline renderer
 * know which modules are wired without a packet of our own.
 */
@Mixin(value = AbstractPanelBlockEntity.class, remap = false)
public abstract class AbstractPanelBlockEntityMixin {

    @Inject(method = "saveAdditional", at = @At("TAIL"), remap = false)
    private void dbd$writeBindings(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        PanelBindings.write((AbstractPanelBlockEntity) (Object) this, tag);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"), remap = false)
    private void dbd$readBindings(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        PanelBindings.read((AbstractPanelBlockEntity) (Object) this, tag);
    }
}
