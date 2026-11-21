package mcagenttest.mixin;

import java.util.function.BooleanSupplier;
import mcagenttest.Observer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
abstract class Container {
    @Unique private boolean carryingDiamond;
    @Unique private boolean inChest;

    @Inject(method="tickServer", at=@At("RETURN"))
    private void checkItem(BooleanSupplier budget, CallbackInfo ci) {
        if (!Observer.recording) return;
        var server = (IntegratedServer)(Object)this;
        if (Observer.measureWorld) System.out.printf("AGENT_WORLD %b %d%n", server.isPaused(), server.overworld().getGameTime());
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var menu = players.getFirst().containerMenu;
        if (menu instanceof ChestMenu && !inChest) System.out.println("AGENT_OPEN chest");
        inChest = menu instanceof ChestMenu;
        boolean hasDiamond = menu.getCarried().is(Items.DIAMOND);
        if (hasDiamond && !carryingDiamond) System.out.println("AGENT_CARRY diamond");
        carryingDiamond = hasDiamond;
    }
}
