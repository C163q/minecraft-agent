package mcagenttest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A fresh target and landing area for real client input against the integrated server. */
public final class ActionFixture {
    private static final BlockPos TARGET = new BlockPos(12, 96, 3);
    private static final BlockPos TILL_TARGET = TARGET.below();
    private static int targetUses;
    private BlockPos target = TARGET;
    private SavedPlayer saved;

    // Called on the integrated server thread after handling a vanilla interaction packet.
    public static void recordUse(BlockPos position) {
        if (position.equals(TARGET)) targetUses++;
    }

    private record SavedPlayer(Vec3 position, float yaw, float pitch, GameType mode,
                               ItemStack main, ItemStack off, float health, float absorption, double maxAbsorption) {
        SavedPlayer(ServerPlayer player) {
            this(player.position(), player.getYRot(), player.getXRot(), player.gameMode(),
                player.getMainHandItem().copy(), player.getOffhandItem().copy(), player.getHealth(),
                player.getAbsorptionAmount(), player.getAttribute(Attributes.MAX_ABSORPTION).getBaseValue());
        }

        void restore(ServerPlayer player) {
            player.setGameMode(mode);
            player.setItemInHand(InteractionHand.MAIN_HAND, main);
            player.setItemInHand(InteractionHand.OFF_HAND, off);
            player.inventoryMenu.broadcastChanges();
            player.setHealth(health);
            player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(maxAbsorption);
            player.setAbsorptionAmount(absorption);
            player.setDeltaMovement(Vec3.ZERO);
            player.connection.teleport(position.x, position.y, position.z, yaw, pitch);
        }
    }

    void apply(Minecraft mc, String action) throws IOException {
        var server = mc.submit(mc::getSingleplayerServer).join();
        if (action.equals("read") || action.equals("read-lower")) {
            var value = server.submit(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var state = player.level().getBlockState(action.equals("read-lower") ? target.below() : target);
                var open = state.hasProperty(DoorBlock.OPEN) ? state.getValue(DoorBlock.OPEN).toString() : "-";
                return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " " + open + " " + player.onGround()
                    + " " + targetUses;
            }).join();
            Files.writeString(Path.of("action-state"), value);
            return;
        }
        server.submit(() -> setup(server.getPlayerList().getPlayers().getFirst(), action)).join();
    }

    private void setup(ServerPlayer player, String action) {
        if (action.equals("restore")) {
            saved.restore(player);
            saved = null;
            return;
        }
        if (saved == null) saved = new SavedPlayer(player);
        targetUses = 0;
        var level = player.level();
        // Clear both halves directly so changing scenes cannot drop a door via a shape update.
        int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(TARGET, Blocks.AIR.defaultBlockState(), clearFlags);
        level.setBlock(TILL_TARGET, Blocks.AIR.defaultBlockState(), clearFlags);
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(11, 80, -1, 14, 98, 6))) {
            item.discard();
        }
        for (int x = 11; x <= 13; x++) {
            for (int z = -1; z <= 5; z++) {
                var floor = new BlockPos(x, 94, z);
                var surface = action.equals("edge") && z > 0 ? Blocks.AIR : Blocks.STONE;
                level.setBlockAndUpdate(floor, surface.defaultBlockState());
                level.setBlockAndUpdate(floor.below(14), Blocks.STONE.defaultBlockState());
            }
        }
        target = TARGET;
        double x = 12.5;
        double z = .5;
        float pitch = 0;
        ItemStack main = ItemStack.EMPTY;
        ItemStack off = ItemStack.EMPTY;
        switch (action) {
            case "door", "creative-door" -> {
                var bottom = Blocks.OAK_DOOR.defaultBlockState();
                var top = bottom.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
                level.setBlockAndUpdate(TILL_TARGET, bottom);
                level.setBlockAndUpdate(TARGET, top);
                // The same ray reaches both the closed face and the open door's edge.
                var openShape = top.setValue(DoorBlock.OPEN, true).getShape(level, TARGET).bounds();
                x = 12 + (openShape.minX + openShape.maxX) / 2;
            }
            case "chest" -> level.setBlockAndUpdate(TARGET, Blocks.CHEST.defaultBlockState());
            case "till" -> {
                target = TILL_TARGET;
                level.setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState());
                off = new ItemStack(Items.IRON_HOE);
                pitch = 12;
            }
            case "strip" -> {
                level.setBlockAndUpdate(TARGET, Blocks.OAK_LOG.defaultBlockState());
                main = new ItemStack(Items.IRON_AXE);
            }
            case "miss" -> level.setBlockAndUpdate(TARGET, Blocks.STONE.defaultBlockState());
            case "survival", "creative" -> {
                level.setBlockAndUpdate(TARGET, Blocks.STONE.defaultBlockState());
                main = new ItemStack(Items.IRON_PICKAXE);
            }
            case "edge" -> z = .9;
            case "flat" -> { }
            case "shield" -> off = new ItemStack(Items.SHIELD);
            default -> throw new IllegalArgumentException("unknown action fixture " + action);
        }
        player.setGameMode(action.startsWith("creative") ? GameType.CREATIVE : GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, main);
        player.setItemInHand(InteractionHand.OFF_HAND, off);
        player.inventoryMenu.broadcastChanges();
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.teleport(x, 95, z, 0, pitch);
    }
}
