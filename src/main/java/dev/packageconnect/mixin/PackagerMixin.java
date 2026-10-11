package dev.packageconnect.mixin;

import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import dev.packageconnect.StorageBridge;
import dev.packageconnect.StorageConnection;
import java.util.List;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 将库存查询和提取接入共享存储索引。 Connects packager stock queries and extraction to the shared storage index. */
@Mixin(value = PackagerBlockEntity.class, remap = false)
public abstract class PackagerMixin {
  /**
   * Create 当前库存行为，替换为支持存储的连接。 Active Create inventory behaviour, replaced with a storage-aware
   * connection.
   */
  @Shadow public InvManipulationBehaviour targetInventory;

  @Shadow private InventorySummary availableItems;

  @Unique private boolean packageconnectSharedStock;

  /**
   * 通知 Create 库存变化；目标方法可能修改旧摘要。 Notifies Create about stock changes; the target may mutate the old
   * summary.
   */
  @Shadow
  protected abstract void submitNewArrivals(InventorySummary before, InventorySummary after);

  @Inject(method = "addBehaviours", at = @At("RETURN"))
  private void packageconnectConnect(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
    int index = behaviours.indexOf(targetInventory);
    targetInventory = new StorageConnection((PackagerBlockEntity) (Object) this);
    behaviours.set(index, targetInventory);
  }

  @Inject(method = "getAvailableItems", at = @At("HEAD"), cancellable = true)
  private void packageconnectStock(CallbackInfoReturnable<InventorySummary> cir) {
    if (!(targetInventory.getInventory() instanceof StorageBridge bridge)) {
      // 切回普通容器后，Create 会直接修改旧摘要；只在离开共享库存时复制一次。
      // On fallback Create mutates the previous summary; detach it once when leaving shared stock.
      if (packageconnectSharedStock && availableItems != null) {
        availableItems = availableItems.copy();
      }
      packageconnectSharedStock = false;
      return;
    }
    InventorySummary current = bridge.cache().summary();
    if (current != availableItems) {
      // Create 在到货通知中扣减旧摘要，因此传入副本，保护共享缓存快照。
      // Create subtracts from "before" during promise notification. Never mutate shared cache
      // snapshots.
      submitNewArrivals(availableItems == null ? null : availableItems.copy(), current);
      availableItems = current;
    }
    packageconnectSharedStock = true;
    cir.setReturnValue(current);
  }

  @Redirect(
      method = "attemptToSend",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/simibubi/create/foundation/blockEntity/behaviour/inventory/"
                      + "InvManipulationBehaviour;getInventory()Ljava/lang/Object;"))
  private Object packageconnectIndexed(
      InvManipulationBehaviour behaviour, List<PackagingRequest> requests) {
    IItemHandler inventory = behaviour.getInventory();
    return inventory instanceof StorageBridge bridge ? bridge.packingView(requests) : inventory;
  }

  @Inject(method = "isTargetingSameInventory", at = @At("HEAD"), cancellable = true)
  private void packageconnectSameStorage(
      IdentifiedInventory inventory, CallbackInfoReturnable<Boolean> cir) {
    if (inventory == null || !(targetInventory.getInventory() instanceof StorageBridge bridge)) {
      return;
    }
    if (inventory.identifier() instanceof StorageConnection.Identifier id) {
      cir.setReturnValue(bridge.storageId().equals(id.id()));
    } else if (inventory.handler() instanceof StorageBridge other) {
      cir.setReturnValue(bridge.storageId().equals(other.storageId()));
    }
  }
}
