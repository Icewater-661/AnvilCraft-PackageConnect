package dev.packageconnect;

import com.simibubi.create.content.logistics.packager.PackagingRequest;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.List;

public final class StorageBridge implements IItemHandler {
    final BaseStorage<?> storage;
    public StorageBridge(BaseStorage<?> storage) { this.storage = storage; }
    public StorageCache cache() { return StorageCache.get(storage); }
    public java.util.UUID storageId() { return storage.getId(); }
    @Override public int getSlots() { return storage.getItems().size() + 1; }
    @Override public ItemStack getStackInSlot(int slot) { return storage.getItems().getStackInSlot(slot); }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return storage.getItems().insertItem(stack, simulate); }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return storage.getItems().extractItem(slot, amount, simulate); }
    @Override public int getSlotLimit(int slot) { return storage.getItems().getSlotLimit(slot); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return storage.getItems().isItemValid(slot, stack); }

    public IItemHandler packingView(List<PackagingRequest> requests) {
        StorageCache cache = cache();
        List<ItemStack> keys = requests == null ? cache.keys() : List.of();
        return new IItemHandler() {
            private ItemStack item(int slot) {
                if (requests != null) return requests.isEmpty() ? ItemStack.EMPTY : requests.getFirst().item();
                return slot < 0 || slot >= keys.size() ? ItemStack.EMPTY : keys.get(slot);
            }
            @Override public int getSlots() { return requests == null ? keys.size() : requests.isEmpty() ? 0 : 1; }
            @Override public ItemStack getStackInSlot(int slot) { return extractItem(slot, 64, true); }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack key = item(slot);
                return key.isEmpty() ? ItemStack.EMPTY : StorageCache.get(storage).extract(key, amount, simulate);
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            @Override public int getSlotLimit(int slot) { return 64; }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
        };
    }
}
