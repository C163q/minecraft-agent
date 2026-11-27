package mcagenttest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A platform above the test world with independently known ray targets. */
final class InspectFixture {
    private static final BlockPos TARGET = new BlockPos(0, 96, 3);
    private static final Vec3 FEET = new Vec3(.625, 95, .375);

    private SavedPlayer saved;
    private Entity cow;

    private record SavedPlayer(Vec3 position, float yaw, float pitch, ItemStack mainHand,
                               ItemStack offHand, float health, double maxHealth,
                               float absorption, double maxAbsorption, int food) {
        SavedPlayer(ServerPlayer player) {
            this(player.position(), player.getYRot(), player.getXRot(), player.getMainHandItem().copy(),
                player.getOffhandItem().copy(), player.getHealth(), player.getAttribute(Attributes.MAX_HEALTH).getBaseValue(),
                player.getAbsorptionAmount(), player.getAttribute(Attributes.MAX_ABSORPTION).getBaseValue(),
                player.getFoodData().getFoodLevel());
        }

        void restore(ServerPlayer player) {
            player.setItemInHand(InteractionHand.MAIN_HAND, mainHand);
            player.setItemInHand(InteractionHand.OFF_HAND, offHand);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(maxHealth);
            player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(maxAbsorption);
            player.setHealth(health);
            player.setAbsorptionAmount(absorption);
            player.getFoodData().setFoodLevel(food);
            player.inventoryMenu.broadcastChanges();
            player.connection.teleport(position.x, position.y, position.z, yaw, pitch);
        }
    }

    void apply(Minecraft mc, String action) throws IOException {
        switch (action) {
            case "air" -> mc.submit(() -> mc.player.setAirSupply(210)).join();
            case "cow" -> turn(mc, -90, 15);
            case "sky" -> turn(mc, -90, -90);
            case "setup", "restore" -> updateServer(mc, action.equals("setup"));
            default -> throw new IllegalArgumentException("unknown inspect fixture action " + action);
        }
    }

    private void turn(Minecraft mc, float yaw, float pitch) {
        mc.submit(() -> mc.player.snapTo(mc.player.position(), yaw, pitch)).join();
    }

    private void updateServer(Minecraft mc, boolean setup) throws IOException {
        var server = mc.submit(mc::getSingleplayerServer).join();
        int entityId = server.submit(() -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var level = player.level();
            var floor = setup ? Blocks.STONE : Blocks.AIR;
            for (int x = -1; x <= 3; x++) {
                for (int z = -1; z <= 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 94, z), floor.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(TARGET, (setup ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
            if (!setup) {
                cow.discard();
                saved.restore(player);
                return cow.getId();
            }
            saved = new SavedPlayer(player);
            var animal = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND));
            animal.setNoAi(true);
            animal.setSilent(true);
            animal.snapTo(2.625, 95, .375, 0, 0);
            if (!level.addFreshEntity(animal)) throw new IllegalStateException("could not spawn fixture cow");
            cow = animal;
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 3));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH, 7));
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(24);
            player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(8);
            player.setHealth(13.5F);
            player.setAbsorptionAmount(4);
            player.getFoodData().setFoodLevel(15);
            player.inventoryMenu.broadcastChanges();
            player.connection.teleport(FEET.x, FEET.y, FEET.z, 0, 3);
            return cow.getId();
        }).join();

        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!mc.submit(() -> {
            var player = mc.player;
            if (!setup) return player.position().distanceToSqr(saved.position) < .01
                && player.getOffhandItem().isEmpty() && player.getHealth() == saved.health;
            return player.position().equals(FEET) && player.getHealth() == 13.5F
                && player.getMainHandItem().is(Items.BREAD) && player.getOffhandItem().is(Items.TORCH)
                && mc.level.getBlockState(TARGET).is(Blocks.REDSTONE_BLOCK) && mc.level.getEntity(entityId) != null;
        }).join()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("inspect fixture did not synchronize");
            java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
        }
        if (setup) Files.writeString(Path.of("inspect-cow-id"), Integer.toString(entityId));
    }
}
