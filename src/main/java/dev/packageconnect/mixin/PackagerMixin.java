package dev.packageconnect.mixin;

import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import dev.packageconnect.StorageBridge;
import dev.packageconnect.StorageConnection;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(value = PackagerBlockEntity.class, remap = false)
public abstract class PackagerMixin {
    @Shadow public InvManipulationBehaviour targetInventory;
    @Shadow private InventorySummary availableItems;
    @Shadow protected abstract void submitNewArrivals(InventorySummary before, InventorySummary after);

    @Inject(method = "addBehaviours", at = @At("RETURN"))
    private void packageconnect$connect(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        int index = behaviours.indexOf(targetInventory);
        targetInventory = new StorageConnection((PackagerBlockEntity)(Object)this);
        behaviours.set(index, targetInventory);
    }

    @Inject(method = "getAvailableItems", at = @At("HEAD"), cancellable = true)
    private void packageconnect$stock(CallbackInfoReturnable<InventorySummary> cir) {
        if (!(targetInventory.getInventory() instanceof StorageBridge bridge)) return;
        InventorySummary current = bridge.cache().summary();
        if (current != availableItems) {
            // Create subtracts from "before" during promise notification. Never mutate shared cache snapshots.
            submitNewArrivals(availableItems == null ? null : availableItems.copy(), current);
            availableItems = current;
        }
        cir.setReturnValue(current);
    }

    @Redirect(method = "attemptToSend", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/inventory/InvManipulationBehaviour;getInventory()Ljava/lang/Object;"))
    private Object packageconnect$indexed(InvManipulationBehaviour behaviour, List<PackagingRequest> requests) {
        IItemHandler inventory = behaviour.getInventory();
        return inventory instanceof StorageBridge bridge ? bridge.packingView(requests) : inventory;
    }

    @Inject(method = "isTargetingSameInventory", at = @At("HEAD"), cancellable = true)
    private void packageconnect$sameStorage(IdentifiedInventory inventory, CallbackInfoReturnable<Boolean> cir) {
        if (inventory == null || !(targetInventory.getInventory() instanceof StorageBridge bridge)) return;
        if (inventory.identifier() instanceof StorageConnection.Identifier id)
            cir.setReturnValue(bridge.storageId().equals(id.id()));
        else if (inventory.handler() instanceof StorageBridge other)
            cir.setReturnValue(bridge.storageId().equals(other.storageId()));
    }
}
