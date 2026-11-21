package mcagenttest;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;

/** One controlled stimulus on a dispatched tick in the disposable test world. */
public final class DamageStimulus {
    public static void tick() {
        var file = Path.of("test-hit");
        if (!Files.exists(file)) return;
        try {
            String mode = Files.readString(file).trim();
            Files.delete(file);
            var mc = Minecraft.getInstance();
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
        if (mode.equals("change-level")) {
            var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            player.teleportTo(nether, 0, 90, 0, java.util.Set.of(), 0, 0, false);
            return;
        }
        if (mode.equals("respawn")) {
            var next = level.getServer().getPlayerList().respawn(player, false,
                net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            next.setHealth(12);
            System.out.println("AGENT_RESPAWN " + next.getId());
            return;
        }
        player.damageCooldownTime = mode.startsWith("repeat") ? 20 : 0;
        if (mode.equals("gold")) {
            player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(12);
            player.setAbsorptionAmount(10);
        } else if (mode.equals("shield")) {
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            player.startUsingItem(InteractionHand.OFF_HAND);
            try {
                var remaining = LivingEntity.class.getDeclaredField("useItemRemaining");
                remaining.setAccessible(true);
                remaining.setInt(player, remaining.getInt(player) - 10);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
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
        if (mode.equals("shield")) {
            player.stopUsingItem();
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
    }
}
