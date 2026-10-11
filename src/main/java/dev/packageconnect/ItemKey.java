package dev.packageconnect;

import dev.dubhe.anvilcraft.api.itemhandler.unlimited.SpaceSizeItemStacksResourceHandler;
import net.minecraft.world.item.ItemStack;

/** 持有独立的物品及组件快照，不向调用方暴露可变键。 Owns an item/component snapshot without exposing its mutable key. */
final class ItemKey {
  private final ItemStack stack;
  private final int hash;
  private final int spacePerItem;

  ItemKey(ItemStack stack) {
    this.stack = stack.copyWithCount(1);
    hash = ItemStack.hashItemAndComponents(this.stack);
    spacePerItem = SpaceSizeItemStacksResourceHandler.computeSpace(this.stack, 1);
  }

  boolean matches(ItemStack other) {
    return ItemStack.isSameItemSameComponents(stack, other);
  }

  ItemStack copyStack() {
    return stack.copy();
  }

  int spacePerItem() {
    return spacePerItem;
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ItemKey key && matches(key.stack);
  }
}
