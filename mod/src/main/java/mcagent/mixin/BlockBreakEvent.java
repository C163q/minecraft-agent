package mcagent.mixin;

import mcagent.ServerActionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(ServerPlayerGameMode.class)
abstract class BlockBreakEvent {
    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;

    @Inject(method = "destroyBlock", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
        locals = LocalCapture.CAPTURE_FAILHARD)
    private void observeRemoval(BlockPos position, CallbackInfoReturnable<Boolean> ci,
                                BlockEntity blockEntity, Block originalBlock) {
        if (!ServerActionEvents.isLocalPlayer(player)) return;

        // playerWillDestroy can remove a door's other half and trigger removal of the clicked half.
        var before = originalBlock.defaultBlockState();
        if (before.isAir() || level.getBlockState(position).getBlock() == originalBlock) return;
        var data = ServerActionEvents.blockPosition(level, position);
        data.addProperty("id", ServerActionEvents.blockId(before));
        ServerActionEvents.send(player, "BlockBroken", data);
    }
}
