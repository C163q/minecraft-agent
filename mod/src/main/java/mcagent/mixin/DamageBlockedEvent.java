package mcagent.mixin;

import com.google.gson.JsonObject;
import mcagent.ServerActionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntity.class)
abstract class DamageBlockedEvent {
    @Redirect(method = "applyItemBlocking", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/component/BlocksAttacks;hurtBlockingItem("
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;F)V"))
    private void observeBlockedDamage(BlocksAttacks blocking, Level level, ItemStack stack, LivingEntity defender,
                                      InteractionHand hand, float blockedDamage) {
        var player = defender instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        var item = blockedDamage > 0 && player != null && ServerActionEvents.isLocalPlayer(player)
            ? ServerActionEvents.itemId(stack) : null;
        blocking.hurtBlockingItem(level, stack, defender, hand, blockedDamage);
        if (item != null) {
            var data = new JsonObject();
            data.addProperty("item", item);
            data.addProperty("blocked_damage", blockedDamage);
            ServerActionEvents.send(player, "DamageBlocked", data);
        }
    }
}
