package mcagent.mixin;

import mcagent.ServerActionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class BlockUseEvent {
    @Redirect(method = "handleUseItemOn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn("
            + "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult observeAcceptedUse(ServerPlayerGameMode gameMode, ServerPlayer player,
                                                  Level level, ItemStack stack, InteractionHand hand,
                                                  BlockHitResult hit) {
        if (!ServerActionEvents.isLocalPlayer(player)) return gameMode.useItemOn(player, level, stack, hand, hit);

        var position = hit.getBlockPos();
        var before = level.getBlockState(position);
        var item = ServerActionEvents.itemId(stack);
        var result = gameMode.useItemOn(player, level, stack, hand, hit);
        if (result.consumesAction()) {
            var data = ServerActionEvents.blockPosition(level, position);
            data.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand");
            data.addProperty("item", item);
            data.add("before", ServerActionEvents.blockState(before));
            data.add("after", ServerActionEvents.blockState(level.getBlockState(position)));
            ServerActionEvents.send(player, "BlockInteracted", data);
        }
        return result;
    }
}
