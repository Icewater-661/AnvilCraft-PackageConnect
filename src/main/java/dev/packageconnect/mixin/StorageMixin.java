package dev.packageconnect.mixin;

import dev.anvilcraft.lib.v2.util.stack.UnlimitedItemStack;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.packageconnect.StorageCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BaseStorage.class, remap = false)
public abstract class StorageMixin {
    @Inject(method = "onContentsChanged", at = @At("RETURN"))
    private void packageconnect$changed(int index, UnlimitedItemStack original, CallbackInfo ci) {
        StorageCache.changed((BaseStorage<?>)(Object)this, index);
    }
}
