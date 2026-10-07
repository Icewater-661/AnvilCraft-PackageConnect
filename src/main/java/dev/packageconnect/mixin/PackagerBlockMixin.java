package dev.packageconnect.mixin;

import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import dev.packageconnect.StorageConnection;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PackagerBlock.class, remap = false)
public abstract class PackagerBlockMixin {
    @Inject(method = "getStateForPlacement", at = @At("HEAD"), cancellable = true)
    private void packageconnect$orient(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        for (Direction face : context.getNearestLookingDirections()) {
            var neighbor = context.getClickedPos().relative(face);
            if (StorageConnection.resolveBlock(context.getLevel(), neighbor) != null) {
                // No capabilities or SavedData are queried for our storage: also works on the client
                // and for multipart faces without a block entity of their own.
                var block = (PackagerBlock)(Object)this;
                cir.setReturnValue(block.defaultBlockState()
                    .setValue(PackagerBlock.POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()))
                    .setValue(PackagerBlock.FACING, face.getOpposite()));
                return;
            }
            // Keep Create's original nearest-direction priority when another container is preferred.
            var be = context.getLevel().getBlockEntity(neighbor);
            if (!(be instanceof PackagerBlockEntity) && be != null && be.hasLevel()
                && be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null) != null)
                return;
        }
    }
}
