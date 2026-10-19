package io.github.ondreamqwq.dbd.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>Both hooks are fenced off. They sit directly in the vanilla block entity save/load path, and an
 * exception thrown from there does not just lose our data: it aborts the packet the client is applying,
 * which leaves the chunk desynced (blocks that will not break and vanish when you click them).
 */
@Mixin(value = AbstractPanelBlockEntity.class, remap = false)
public abstract class AbstractPanelBlockEntityMixin {

    private static final Logger DBD_LOG = LoggerFactory.getLogger("drive_by_dashpanels");

    @Inject(method = "saveAdditional", at = @At("TAIL"), remap = false)
    private void dbd$writeBindings(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        try {
            PanelBindings.write((AbstractPanelBlockEntity) (Object) this, tag);
        } catch (RuntimeException | LinkageError e) {
            DBD_LOG.error("could not write wire bindings to a panel; keeping the panel itself intact", e);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"), remap = false)
    private void dbd$readBindings(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        try {
            PanelBindings.read((AbstractPanelBlockEntity) (Object) this, tag);
        } catch (RuntimeException | LinkageError e) {
            DBD_LOG.error("could not read wire bindings from a panel; keeping the panel itself intact", e);
        }
    }
}
