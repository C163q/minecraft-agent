package mcagent.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
interface HealthField {
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> minecraftAgentHealth() { throw new AssertionError(); }
}
