package mcagenttest;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;

/** One controlled stimulus on a dispatched tick in the disposable test world. */
public final class DamageStimulus {
    public static boolean dropAtNextMarker;

    public static void tick() {
        var file = Path.of("test-hit");
        if (!Files.exists(file)) return;
        try {
            String mode = Files.readString(file).trim();
            Files.delete(file);
            var mc = Minecraft.getInstance();
            if (mode.equals("slide-then-push")) {
                // A free sideways substep and a blocked forward substep in the same tick.
                mc.player.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(.015, 0, 0));
                mc.player.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(0, 0, .05));
                return;
            }
            if (mode.equals("late-drop")) {
                dropAtNextMarker = true;
                return;
            }
            if (mode.equals("client-drop")) {
                mc.player.hurtTo(20);
                mc.player.hurtTo(16);
                mc.player.hurtTo(16);
                mc.player.hurtTo(20);
                return;
            }
            var server = mc.getSingleplayerServer();
            server.execute(() -> hit(server.getPlayerList().getPlayers().getFirst(), mode));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void hit(ServerPlayer player, String mode) {
        var level = player.level();
        var source = new DamageSource(player.damageSources().mobAttack(player).typeHolder(),
            player.position().add(player.getLookAngle()));
        if (mode.equals("collision-room")) {
            for (int x = -3; x <= 6; x++) for (int z = -6; z <= 3; z++) {
                var floor = new net.minecraft.core.BlockPos(x, 94, z);
                level.setBlockAndUpdate(floor, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                for (int height = 1; height <= 3; height++) {
                    var block = x == -2 || z == 2 ? net.minecraft.world.level.block.Blocks.STONE
                                                    : net.minecraft.world.level.block.Blocks.AIR;
                    level.setBlockAndUpdate(floor.above(height), block.defaultBlockState());
                }
            }
            player.connection.teleport(0.5, 95, 0.5, 0, 0);
            return;
        }
        if (mode.startsWith("lethal-")) {
            var server = level.getServer();
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.IMMEDIATE_RESPAWN,
                mode.equals("lethal-immediate"), server);
            player.setItemInHand(InteractionHand.OFF_HAND,
                mode.equals("lethal-totem") ? new ItemStack(Items.TOTEM_OF_UNDYING) : ItemStack.EMPTY);
            player.damageCooldownTime = 0;
            boolean accepted = player.hurtServer(level, player.damageSources().generic(), 1000);
            System.out.println("AGENT_LETHAL " + mode + " dead=" + player.isDeadOrDying() +
                " accepted=" + accepted + " offhandEmpty=" + player.getOffhandItem().isEmpty());
            return;
        }
        if (mode.equals("change-level")) {
            var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            player.teleportTo(nether, 0, 90, 0, java.util.Set.of(), 0, 0, false);
            return;
        }
        if (mode.equals("respawn")) {
            var next = level.getServer().getPlayerList().respawn(player, false,
                net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            // Vanilla installs the new player on the connection after PlayerList.respawn returns.
            next.connection.player = next;
            next.connection.resetPosition();
            next.setHealth(12);
            System.out.println("AGENT_RESPAWN " + next.getId());
            return;
        }
        player.damageCooldownTime = mode.startsWith("repeat") ? 20 : 0;
        if (mode.equals("gold")) {
            player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(12);
            player.setAbsorptionAmount(10);
        } else if (mode.endsWith("shield")) {
            var shield = new ItemStack(Items.SHIELD);
            var vanilla = shield.get(DataComponents.BLOCKS_ATTACKS);
            var reductions = vanilla.damageReductions();
            if (mode.equals("partial-shield")) {
                reductions = reductions.stream().map(r -> new BlocksAttacks.DamageReduction(
                    r.horizontalBlockingAngle(), r.type(), r.base(), r.factor() * 0.5f)).toList();
            }
            // Zero raise delay makes the shield active on this exact test tick.
            shield.set(DataComponents.BLOCKS_ATTACKS, new BlocksAttacks(0, vanilla.disableCooldownScale(),
                reductions, vanilla.itemDamage(), vanilla.bypassedBy(), vanilla.blockSound(), vanilla.disableSound()));
            player.setItemInHand(InteractionHand.OFF_HAND, shield);
            player.startUsingItem(InteractionHand.OFF_HAND);
        } else if (mode.equals("restore")) {
            player.setHealth(20);
            player.setAbsorptionAmount(0);
            return;
        }
        float amount = mode.equals("micro") ? 0.1f : mode.equals("repeat-strong") ? 2 : 1;
        boolean accepted = player.hurtServer(level, source, amount);
        System.out.printf("AGENT_HIT %s %s hp=%.2f gold=%.2f%n", mode, accepted, player.getHealth(), player.getAbsorptionAmount());
        if (mode.equals("cooldown")) {
            for (int strength : new int[]{1, 2}) {
                boolean again = player.hurtServer(level, source, strength);
                System.out.println("AGENT_REPEAT " + strength + " " + again);
            }
        }
        if (mode.endsWith("shield")) {
            player.stopUsingItem();
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
    }
}
