package dev.packageconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import dev.anvilcraft.lib.v2.util.stack.UnlimitedItemStack;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.InfiniteItemStacksResourceHandler;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.OverflowDisposalItemStacksResourceHandler;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.TypeLimitItemStacksResourceHandler;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.UnlimitedItemStacksResourceHandler;
import dev.dubhe.anvilcraft.init.item.ModComponents;
import dev.dubhe.anvilcraft.item.property.component.Eternal;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.saved.storage.IStorageType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 使用真实 handler 验证索引和容量；不启动服务器或访问 SavedData。 Tests indexes and capacity with real handlers, without
 * starting a server or accessing SavedData.
 */
class StorageRegressionTest {
  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @BeforeEach
  @AfterEach
  void clearCaches() {
    StorageCache.clear();
  }

  @Test
  void countChangesKeepOrderAndAvoidRebuilds() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    storage.getItems().insertItem(copper(100), false);
    storage.getItems().insertItem(new ItemStack(Items.IRON_INGOT, 20), false);
    var cache = StorageCache.get(storage);
    var before = cache.summary();
    final long scans = StorageCache.statistics().scannedSlots();

    storage.getItems().extractItem(0, 10, false);

    assertEquals(90, cache.count(copper(1)));
    assertEquals(100, before.getCountOf(copper(1)));
    assertNotSame(before, cache.summary());
    assertSame(cache.summary(), cache.summary());
    assertEquals(Items.COPPER_INGOT, cache.itemTypes().getFirst().getItem());
    assertEquals(scans, StorageCache.statistics().scannedSlots());
  }

  @Test
  void componentVariantsAndReturnedStacksCannotCorruptKeys() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    ItemStack named = copper(9);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("variant"));
    storage.getItems().insertItem(named, false);
    storage.getItems().insertItem(copper(7), false);
    var cache = StorageCache.get(storage);

    cache.itemTypes().getFirst().set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
    cache.summary().getStacks().getFirst().stack.setCount(0);

    assertEquals(9, cache.count(named));
    assertEquals(7, cache.count(copper(1)));
    assertEquals(9, cache.extract(named, 64, true).getCount());
    assertEquals(2, cache.occupiedSlots());
  }

  @Test
  void simulationAndLargeCountsRemainExact() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    storage.getItems().insertItem(copper(Integer.MAX_VALUE), false);
    storage.getItems().insertItem(copper(100), false);
    var cache = StorageCache.get(storage);
    long expected = (long) Integer.MAX_VALUE + 100;
    var summary = cache.summary();
    final long scans = StorageCache.statistics().scannedSlots();
    for (int attempt = 0; attempt < 100; attempt++) {
      assertEquals(64, cache.extract(copper(1), 64, true).getCount());
    }
    assertEquals(expected, cache.count(copper(1)));
    assertSame(summary, cache.summary());
    assertEquals(BigItemStack.INF, summary.getCountOf(copper(1)));
    assertEquals(scans, StorageCache.statistics().scannedSlots());
    assertEquals(0, StorageCache.statistics().extractionSlots());
    assertEquals(64, cache.extract(copper(1), 64, false).getCount());
    assertEquals(expected - 64, cache.count(copper(1)));
    assertEquals(1, StorageCache.statistics().extractionSlots());
  }

  @Test
  void extractionVisitsOnlyNeededDuplicateSlots() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    for (int slot = 0; slot < 512; slot++) {
      storage.getItems().insertItem(slot, copper(2), false);
    }
    var cache = StorageCache.get(storage);
    assertEquals(64, cache.extract(copper(1), 64, false).getCount());
    assertEquals(32, StorageCache.statistics().extractionSlots());
    assertEquals(960, cache.count(copper(1)));
    assertEquals(480, cache.occupiedSlots());
    assertEquals(2, storage.getItems().getAmountAsLong(32));
    assertEquals(512, StorageCache.statistics().scannedSlots());
  }

  @Test
  void bulkReplacementInvalidatesOnceAndReplacesSlotTypes() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    storage.getItems().insertItem(copper(10), false);
    final var cache = StorageCache.get(storage);
    var replacement = new InfiniteItemStacksResourceHandler();
    replacement.insertItem(new ItemStack(Items.IRON_INGOT, 12), false);
    storage.getItems().sync(replacement);
    assertEquals(1, StorageCache.statistics().builds());
    assertEquals(0, cache.count(copper(1)));
    assertEquals(12, cache.count(new ItemStack(Items.IRON_INGOT)));
    assertEquals(2, StorageCache.statistics().builds());
    assertEquals(1, cache.occupiedSlots());
  }

  @Test
  void replacingHandlerForSameIdRejectsStaleCallbacks() {
    UUID id = UUID.randomUUID();
    var previous = new InfiniteStorage(id);
    previous.getItems().insertItem(copper(10), false);
    var oldCache = StorageCache.get(previous);
    var replacement = new InfiniteStorage(id);
    replacement.getItems().insertItem(copper(20), false);
    var current = StorageCache.get(replacement);
    assertNotSame(oldCache, current);
    assertSame(current, StorageCache.get(replacement));
    previous.getItems().extractItem(0, 5, false);
    assertEquals(20, current.count(copper(1)));
    assertEquals(1, StorageCache.statistics().caches());
  }

  @Test
  void cacheRetentionUsesAccessOrderAndClearResetsCounters() {
    var oldest = new InfiniteStorage(UUID.randomUUID());
    var kept = new InfiniteStorage(UUID.randomUUID());
    final var evictedCache = StorageCache.get(oldest);
    final var keptCache = StorageCache.get(kept);
    for (int index = 0; index < 254; index++) {
      StorageCache.get(new InfiniteStorage(UUID.randomUUID()));
    }
    StorageCache.get(kept);
    StorageCache.get(new InfiniteStorage(UUID.randomUUID()));
    assertEquals(256, StorageCache.statistics().caches());
    assertSame(keptCache, StorageCache.get(kept));
    assertNotSame(evictedCache, StorageCache.get(oldest));
    var snapshot = StorageCache.statistics();
    StorageCache.clear();
    assertEquals(256, snapshot.caches());
    assertEquals(0, StorageCache.statistics().caches());
    assertEquals(0, StorageCache.statistics().builds());
  }

  @Test
  void bridgeIncludesSlotsAfterHolesAndFollowsLiveRequestHead() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    storage.getItems().insertItem(copper(10), false);
    storage.getItems().insertItem(new ItemStack(Items.IRON_INGOT, 12), false);
    storage.getItems().extractItem(0, 10, false);
    var bridge = new StorageBridge(storage);
    assertEquals(3, bridge.getSlots());
    assertEquals(Items.IRON_INGOT, bridge.getStackInSlot(1).getItem());
    var requests = new ArrayList<PackagingRequest>();
    requests.add(request(copper(1)));
    requests.add(request(new ItemStack(Items.IRON_INGOT)));
    var view = bridge.packingView(requests);
    assertTrue(view.getStackInSlot(0).isEmpty());
    requests.removeFirst();
    assertTrue(view.extractItem(-1, 12, false).isEmpty());
    assertTrue(view.extractItem(1, 12, false).isEmpty());
    assertEquals(12, view.extractItem(0, 12, false).getCount());
    requests.clear();
    assertEquals(0, view.getSlots());
    assertTrue(view.extractItem(0, 64, false).isEmpty());
    assertEquals(4, view.insertItem(0, copper(4), false).getCount());
  }

  @Test
  void ordinaryPackingKeepsItsTypeSnapshotDuringMutations() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    storage.getItems().insertItem(copper(10), false);
    storage.getItems().insertItem(new ItemStack(Items.IRON_INGOT, 12), false);
    var view = new StorageBridge(storage).packingView(null);
    assertEquals(10, view.extractItem(0, 64, false).getCount());
    assertTrue(view.extractItem(0, 64, false).isEmpty());
    assertEquals(12, view.extractItem(1, 64, false).getCount());
    assertEquals(0, StorageCache.get(storage).occupiedSlots());
  }

  @Test
  void capacityChecksWholePackageBeforeAnyInsertion() {
    var storage = new SpaceStorage();
    List<ItemStack> tooMuch = List.of(copper(40), new ItemStack(Items.IRON_INGOT, 40));
    assertFalse(StorageUnpacking.insert(storage, tooMuch, false));
    assertEquals(0, storage.getItems().size());
    List<ItemStack> contents = List.of(copper(32), new ItemStack(Items.IRON_INGOT, 32));
    assertTrue(StorageUnpacking.insert(storage, contents, true));
    assertEquals(0, storage.getItems().size());
    assertTrue(StorageUnpacking.insert(storage, contents, false));
    assertEquals(64, StorageCache.get(storage).usedSpace());
    assertEquals(32, contents.getFirst().getCount());
    assertFalse(StorageUnpacking.fits(storage, List.of(copper(1))));
  }

  @Test
  void capacityUsesNativeWeightIncludingDataComponents() {
    var storage = new SpaceStorage();
    ItemStack unusual = copper(2);
    unusual.set(DataComponents.MAX_STACK_SIZE, 3);
    assertTrue(StorageUnpacking.insert(storage, List.of(unusual), false));
    assertEquals(44, StorageCache.get(storage).usedSpace());
    assertFalse(StorageUnpacking.fits(storage, List.of(new ItemStack(Items.ENDER_PEARL, 6))));
    assertTrue(
        StorageUnpacking.insert(storage, List.of(new ItemStack(Items.ENDER_PEARL, 5)), false));
    assertEquals(64, StorageCache.get(storage).usedSpace());
  }

  @Test
  void disposalRetainsOnlyAvailableCapacity() {
    var storage = new SpaceStorage();
    storage.getItems().setDispose(true);
    List<ItemStack> contents = List.of(copper(80), new ItemStack(Items.IRON_INGOT, 20));
    assertTrue(StorageUnpacking.insert(storage, contents, true));
    assertEquals(0, storage.getItems().size());
    assertTrue(StorageUnpacking.insert(storage, contents, false));
    assertEquals(64, StorageCache.get(storage).count(copper(1)));
    assertEquals(0, StorageCache.get(storage).count(new ItemStack(Items.IRON_INGOT)));
    assertEquals(80, contents.getFirst().getCount());
  }

  @Test
  void eternalItemsCannotBeDisposedAndInsertionOrderMatters() {
    var storage = new SpaceStorage();
    storage.getItems().setDispose(true);
    ItemStack eternal = copper(8);
    eternal.set(ModComponents.ETERNAL, Eternal.INSTANCE);
    assertFalse(StorageUnpacking.insert(storage, List.of(copper(80), eternal), false));
    assertEquals(0, storage.getItems().size());
    assertTrue(StorageUnpacking.insert(storage, List.of(eternal, copper(80)), true));
    assertEquals(0, storage.getItems().size());
    assertTrue(StorageUnpacking.insert(storage, List.of(eternal, copper(80)), false));
    assertEquals(8, StorageCache.get(storage).count(eternal));
    assertEquals(56, StorageCache.get(storage).count(copper(1)));
  }

  @Test
  void capacityPlansAgreeWithNativeSequentialInsertion() {
    final Random random = new Random(0x5041434bL);
    ItemStack named = copper(1);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("variant"));
    ItemStack eternal = copper(1);
    eternal.set(ModComponents.ETERNAL, Eternal.INSTANCE);
    ItemStack unusual = copper(1);
    unusual.set(DataComponents.MAX_STACK_SIZE, 3);
    List<ItemStack> types =
        List.of(
            copper(1),
            new ItemStack(Items.IRON_INGOT),
            new ItemStack(Items.ENDER_PEARL),
            named,
            eternal,
            unusual);
    List<Supplier<BaseStorage<?>>> factories =
        List.of(() -> new InfiniteStorage(UUID.randomUUID()), SpaceStorage::new, TypedStorage::new);
    for (var factory : factories) {
      for (int scenario = 0; scenario < 120; scenario++) {
        var storage = factory.get();
        var reference = factory.get();
        if (storage.getItems() instanceof OverflowDisposalItemStacksResourceHandler overflow) {
          boolean dispose = random.nextBoolean();
          overflow.setDispose(dispose);
          ((OverflowDisposalItemStacksResourceHandler) reference.getItems()).setDispose(dispose);
        }
        for (int seed = 0; seed < 3; seed++) {
          ItemStack initial =
              types.get(random.nextInt(types.size())).copyWithCount(random.nextInt(30));
          storage.getItems().insertItem(initial.copy(), false);
          reference.getItems().insertItem(initial.copy(), false);
        }
        List<ItemStack> contents = new ArrayList<>();
        for (int entry = 0; entry < 5; entry++) {
          contents.add(types.get(random.nextInt(types.size())).copyWithCount(random.nextInt(50)));
        }
        boolean nativeFits = true;
        for (ItemStack stack : contents) {
          if (!reference.getItems().insertItem(stack.copy(), false).isEmpty()) {
            nativeFits = false;
            break;
          }
        }
        long[] before = types.stream().mapToLong(stack -> nativeCount(storage, stack)).toArray();
        assertEquals(nativeFits, StorageUnpacking.insert(storage, contents, true));
        for (int index = 0; index < types.size(); index++) {
          assertEquals(before[index], nativeCount(storage, types.get(index)));
        }
        assertEquals(nativeFits, StorageUnpacking.insert(storage, contents, false));
        for (int index = 0; index < types.size(); index++) {
          ItemStack type = types.get(index);
          long expected = nativeFits ? nativeCount(reference, type) : before[index];
          assertEquals(expected, nativeCount(storage, type));
          assertEquals(expected, StorageCache.get(storage).count(type));
        }
      }
    }
  }

  private static long nativeCount(BaseStorage<?> storage, ItemStack type) {
    long count = 0;
    var handler = storage.getItems();
    for (int slot = 0; slot < handler.size(); slot++) {
      if (ItemStack.isSameItemSameComponents(type, handler.getStackInSlot(slot))) {
        count += handler.getAmountAsLong(slot);
      }
    }
    return count;
  }

  @Test
  void typeLimitsAccumulateRepeatedItemsAndComponentVariants() {
    var storage = new TypedStorage();
    ItemStack named = copper(1);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("variant"));
    assertFalse(StorageUnpacking.insert(storage, List.of(copper(40), copper(25)), false));
    assertFalse(
        StorageUnpacking.fits(storage, List.of(copper(1), named, new ItemStack(Items.IRON_INGOT))));
    assertTrue(StorageUnpacking.insert(storage, List.of(copper(40), copper(24), named), false));
    assertEquals(64, StorageCache.get(storage).count(copper(1)));
    assertEquals(1, StorageCache.get(storage).count(named));
    assertTrue(StorageUnpacking.fits(storage, List.of(named)));
    assertFalse(StorageUnpacking.fits(storage, List.of(new ItemStack(Items.IRON_INGOT))));
  }

  @Test
  void migratedDuplicateEntriesConsumePhysicalTypeSlots() {
    var source = new InfiniteStorage(UUID.randomUUID());
    source.getItems().insertItem(0, copper(10), false);
    source.getItems().insertItem(1, copper(10), false);
    var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    var storage = new TypedStorage();
    storage.getItems().deserializeNBT(registries, source.getItems().serializeNBT(registries));
    var cache = StorageCache.get(storage);
    assertEquals(2, cache.occupiedSlots());
    assertEquals(20, cache.count(copper(1)));
    assertFalse(StorageUnpacking.insert(storage, List.of(new ItemStack(Items.IRON_INGOT)), false));
    assertTrue(StorageUnpacking.insert(storage, List.of(copper(44)), false));
    assertFalse(StorageUnpacking.fits(storage, List.of(copper(1))));
  }

  @Test
  void typedCapacityStillAllowsExistingTypesAfterLimitReduction() {
    var storage = new TypedStorage();
    StorageUnpacking.insert(storage, List.of(copper(10), new ItemStack(Items.IRON_INGOT)), false);
    storage.getItems().setTypeLimit(1);
    assertTrue(StorageUnpacking.insert(storage, List.of(copper(10)), false));
    assertFalse(StorageUnpacking.insert(storage, List.of(new ItemStack(Items.GOLD_INGOT)), false));
    assertEquals(20, StorageCache.get(storage).count(copper(1)));
  }

  @Test
  void infinitePlanningDoesNotBuildAnUnusedIndex() {
    var storage = new InfiniteStorage(UUID.randomUUID());
    assertTrue(StorageUnpacking.insert(storage, List.of(copper(Integer.MAX_VALUE)), true));
    assertEquals(0, StorageCache.statistics().builds());
    assertEquals(0, storage.getItems().size());
  }

  private static ItemStack copper(int count) {
    return new ItemStack(Items.COPPER_INGOT, count);
  }

  private static PackagingRequest request(ItemStack stack) {
    return PackagingRequest.create(stack, 64, "test", 0, new MutableBoolean(true), 0, 1, null);
  }

  // 测试替换 SavedData 通知，但保留真实 handler 的同步内容回调；Mixin 由世界自检覆盖。
  // Replace SavedData notification while keeping real handler callbacks; world checks cover Mixins.
  private abstract static class TestStorage<T extends UnlimitedItemStacksResourceHandler>
      extends BaseStorage<T> {
    private TestStorage(UUID id) {
      super(id);
    }

    @Override
    protected void onContentsChanged(int index, UnlimitedItemStack original) {
      StorageCache.changed(this, index);
    }

    @Override
    public Holder<IStorageType<?>> getTypeHolder() {
      throw new UnsupportedOperationException("No SavedData in handler regression tests");
    }
  }

  private static final class InfiniteStorage
      extends TestStorage<InfiniteItemStacksResourceHandler> {
    private InfiniteStorage(UUID id) {
      super(id);
    }

    @Override
    protected InfiniteItemStacksResourceHandler constructItemHandler(
        BiConsumer<Integer, UnlimitedItemStack> listener) {
      return new InfiniteItemStacksResourceHandler() {
        @Override
        protected void onContentsChanged(int index, UnlimitedItemStack original) {
          listener.accept(index, original);
        }
      };
    }
  }

  private static final class SpaceStorage
      extends TestStorage<OverflowDisposalItemStacksResourceHandler> {
    private SpaceStorage() {
      super(UUID.randomUUID());
    }

    @Override
    protected OverflowDisposalItemStacksResourceHandler constructItemHandler(
        BiConsumer<Integer, UnlimitedItemStack> listener) {
      return new OverflowDisposalItemStacksResourceHandler(64) {
        @Override
        protected void onContentsChanged(int index, UnlimitedItemStack original) {
          listener.accept(index, original);
        }
      };
    }
  }

  private static final class TypedStorage extends TestStorage<TypeLimitItemStacksResourceHandler> {
    private TypedStorage() {
      super(UUID.randomUUID());
    }

    @Override
    protected TypeLimitItemStacksResourceHandler constructItemHandler(
        BiConsumer<Integer, UnlimitedItemStack> listener) {
      return new TypeLimitItemStacksResourceHandler(2, 64) {
        @Override
        protected void onContentsChanged(int index, UnlimitedItemStack original) {
          listener.accept(index, original);
        }
      };
    }
  }
}
