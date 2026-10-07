package dev.packageconnect;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.UnlimitedItemStacksResourceHandler;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Server-thread-only, shared by storage UUID; no persisted duplicate inventory. */
public final class StorageCache {
    private static final Map<UUID, StorageCache> CACHES = new LinkedHashMap<>(16, .75f, true);
    public static long builds, scannedSlots, updates, indexedExtractions, stockHits;
    final UnlimitedItemStacksResourceHandler handler;
    private final Map<Integer, Slot> slots = new HashMap<>();
    final Map<Key, Entry> entries = new LinkedHashMap<>();
    private InventorySummary summary;
    private boolean dirty = true;
    long space;

    private StorageCache(UnlimitedItemStacksResourceHandler handler) { this.handler = handler; }

    public static StorageCache get(BaseStorage<?> storage) {
        StorageCache cache = CACHES.get(storage.getId());
        if (cache == null || cache.handler != storage.getItems()) {
            cache = new StorageCache(storage.getItems());
            CACHES.put(storage.getId(), cache);
        }
        // Bound retained handler references; an evicted connected storage rebuilds on next access.
        while (CACHES.size() > 256) CACHES.remove(CACHES.keySet().iterator().next());
        cache.ensure();
        return cache;
    }

    public static void changed(BaseStorage<?> storage, int index) {
        StorageCache cache = CACHES.get(storage.getId());
        if (cache == null || cache.handler != storage.getItems()) return;
        if (index < 0 || index >= cache.handler.size()) cache.dirty = true;
        else if (!cache.dirty) cache.refresh(index);
    }

    public static void clear() {
        CACHES.clear();
        builds = scannedSlots = updates = indexedExtractions = stockHits = 0;
    }

    public static String stats() {
        return "caches=" + CACHES.size() + ", builds=" + builds + ", scannedSlots=" + scannedSlots
            + ", slotUpdates=" + updates + ", indexedExtractions=" + indexedExtractions + ", stockCacheHits=" + stockHits;
    }

    void ensure() {
        if (!dirty) return;
        slots.clear(); entries.clear(); space = 0;
        dirty = false;
        builds++;
        for (int i = 0; i < handler.size(); i++) { scannedSlots++; refresh(i); }
        summary = null;
    }

    private void refresh(int index) {
        Slot old = slots.remove(index);
        if (old != null) {
            Entry entry = entries.get(old.key);
            entry.count -= old.count;
            entry.slots.remove(index);
            if (entry.count == 0) entries.remove(old.key);
            space -= old.count * weight(old.key.stack);
        }
        ItemStack current = handler.getStackInSlot(index);
        long count = handler.getAmountAsLong(index);
        if (!current.isEmpty() && count > 0) {
            Key key = new Key(current);
            Entry entry = entries.computeIfAbsent(key, k -> new Entry());
            entry.count += count;
            entry.slots.add(index);
            slots.put(index, new Slot(key, count));
            space += count * weight(current);
        }
        summary = null;
        updates++;
    }

    public InventorySummary summary() {
        ensure();
        if (summary != null) { stockHits++; return summary; }
        InventorySummary result = new InventorySummary();
        // Create treats >=INF as unbounded. Each per-type add is <=INF, so its total cannot overflow.
        entries.forEach((key, entry) -> result.add(key.stack, (int) Math.min(BigItemStack.INF, entry.count)));
        summary = result;
        return summary;
    }

    long count(Key key) { ensure(); Entry e = entries.get(key); return e == null ? 0 : e.count; }
    int occupiedSlots() { ensure(); return slots.size(); }

    ItemStack extract(ItemStack requested, int amount, boolean simulate) {
        ensure(); indexedExtractions++;
        Entry entry = entries.get(new Key(requested));
        if (entry == null || amount <= 0) return ItemStack.EMPTY;
        int count = (int) Math.min(entry.count, Math.min(amount, requested.getMaxStackSize()));
        if (simulate) return requested.copyWithCount(count);
        int remaining = count;
        // Snapshot only matching slots: native mutation callbacks update this entry during extraction.
        for (int slot : new ArrayList<>(entry.slots)) {
            remaining -= handler.extractItem(slot, remaining, false).getCount();
            if (remaining == 0) break;
        }
        return requested.copyWithCount(count - remaining);
    }

    List<ItemStack> keys() { ensure(); return entries.keySet().stream().map(k -> k.stack).toList(); }
    static int weight(ItemStack stack) { return (64 + stack.getMaxStackSize() - 1) / stack.getMaxStackSize(); }

    static final class Key {
        final ItemStack stack;
        private final int hash;
        Key(ItemStack stack) { this.stack = stack.copyWithCount(1); hash = ItemStack.hashItemAndComponents(this.stack); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) { return o instanceof Key k && ItemStack.isSameItemSameComponents(stack, k.stack); }
    }
    static final class Entry { long count; final Set<Integer> slots = new TreeSet<>(); }
    private record Slot(Key key, long count) {}
}
