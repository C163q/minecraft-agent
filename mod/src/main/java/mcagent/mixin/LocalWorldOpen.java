package mcagent.mixin;

import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import mcagent.WorldOpenTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(WorldOpenFlows.class)
abstract class LocalWorldOpen {
    @Shadow @Final private Minecraft minecraft;
    @Unique private WorldOpenTransition worldOpen;

    @Inject(method = {"openWorld", "createFreshLevel"}, at = @At("HEAD"))
    private void beginWorldOpen(CallbackInfo ci) {
        worldOpen = new WorldOpenTransition(minecraft);
    }

    @Inject(method = "createWorldAccess", at = @At("RETURN"))
    private void worldAccessResult(CallbackInfoReturnable<LevelStorageSource.LevelStorageAccess> cir) {
        if (cir.getReturnValue() == null) worldOpen.outcome("failed", minecraft.gui.screen());
    }

    @Inject(method = "createFreshLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/Minecraft;doWorldLoad(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/WorldStem;Ljava/util/Optional;Z)V"))
    private void completeFreshLevel(CallbackInfo ci) {
        worldOpen.outcome("completed", null);
    }

    @Inject(method = "openWorldDoLoad", at = @At("RETURN"))
    private void completeWorldOpen(CallbackInfo ci) {
        worldOpen.outcome("completed", null);
    }

    @Inject(method = "createFreshLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void failFreshLevel(CallbackInfo ci) {
        worldOpen.outcome("failed", minecraft.gui.screen());
    }

    @Redirect(method = {"askForBackup", "openWorldCheckVersionCompatibility", "openWorldLoadLevelData",
        "openWorldLoadLevelStem", "openWorldCheckDiskSpace", "promptBundledPackLoadFailure"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void showOpenWorldPrompt(Gui gui, Screen screen) {
        worldOpen.outcome(screen instanceof AlertScreen ? "failed" : "needs_input", screen);
        gui.setScreen(screen);
    }

    @ModifyArgs(method = {"askForBackup", "openWorldCheckVersionCompatibility"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/BackupConfirmScreen;<init>(Ljava/lang/Runnable;Lnet/minecraft/client/gui/screens/BackupConfirmScreen$Listener;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Z)V"))
    private void wrapBackupResponse(Args args) {
        var flow = worldOpen;
        BackupConfirmScreen.Listener proceed = args.get(1);
        args.set(0, flow.answer(args.<Runnable>get(0), false));
        args.set(1, (BackupConfirmScreen.Listener)(backup, eraseCache) -> {
            flow.resume();
            proceed.proceed(backup, eraseCache);
        });
    }

    @ModifyArgs(method = "openWorldLoadLevelStem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/DatapackLoadFailureScreen;<init>(Ljava/lang/Runnable;Ljava/lang/Runnable;)V"))
    private void wrapSafeModeResponse(Args args) {
        args.set(0, worldOpen.answer(args.<Runnable>get(0), false));
        args.set(1, worldOpen.answer(args.<Runnable>get(1), true));
    }

    @ModifyArg(method = "openWorldLoadLevelData", index = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/RecoverWorldDataScreen;<init>(Lnet/minecraft/client/Minecraft;Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)V"))
    private BooleanConsumer wrapRecoveryResponse(BooleanConsumer response) {
        return new WorldOpenTransition.Recovery(worldOpen, response);
    }

    @ModifyArg(method = "openWorldCheckDiskSpace", index = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/ConfirmScreen;<init>(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V"))
    private BooleanConsumer wrapLowDiskSpaceResponse(BooleanConsumer response) {
        return worldOpen.answer(response);
    }

    @ModifyArg(method = "promptBundledPackLoadFailure", index = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/ConfirmScreen;<init>(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V"))
    private BooleanConsumer wrapBundledPackResponse(BooleanConsumer response) {
        var flow = worldOpen;
        return proceed -> {
            flow.resume();
            response.accept(proceed);
        };
    }

    @Inject(method = "lambda$openWorldLoadBundledResourcePack$2", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Ljava/lang/Runnable;run()V"))
    private void cancelBundledPackLoad(CallbackInfo ci) {
        worldOpen.cancelled();
    }

    @ModifyArg(method = "lambda$upgradeAndOpenWorld$2", index = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/ConfirmScreen;<init>(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V"))
    private BooleanConsumer wrapUpgradeResponse(BooleanConsumer response) {
        return worldOpen.answer(response);
    }

    @Redirect(method = {"lambda$upgradeAndOpenWorld$2", "lambda$tryFileFixAndReportErrors$1", "lambda$tryFileFixAndReportErrors$2"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void showUpgradeResult(Minecraft mc, Screen screen) {
        worldOpen.outcome(screen instanceof ConfirmScreen ? "needs_input" : "failed", screen);
        mc.setScreenAndShow(screen);
    }

    @Redirect(method = "lambda$tryFileFixAndReportErrors$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void cancelFileFix(Minecraft mc, Screen screen) {
        worldOpen.outcome("cancelled", screen);
        mc.setScreenAndShow(screen);
    }

    @Inject(method = "confirmWorldCreation", at = @At("HEAD"))
    private static void beginWorldCreation(Minecraft mc, CreateWorldScreen screen, Lifecycle lifecycle,
                                           Runnable create, boolean skipWarning, CallbackInfo ci) {
        ((WorldOpenTransition.Creation)screen).mcagent$beginWorldCreation(mc);
    }

    @Redirect(method = "confirmWorldCreation", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private static void showCreationPrompt(Gui gui, Screen prompt, Minecraft mc, CreateWorldScreen screen,
                                           Lifecycle lifecycle, Runnable create, boolean skipWarning) {
        ((WorldOpenTransition.Creation)screen).mcagent$worldCreation().outcome("needs_input", prompt);
        gui.setScreen(prompt);
    }

    @Inject(method = "lambda$confirmWorldCreation$0", at = @At("HEAD"))
    private static void beginCreationResponse(Runnable create, Minecraft mc, CreateWorldScreen screen,
                                              boolean proceed, CallbackInfo ci) {
        ((WorldOpenTransition.Creation)screen).mcagent$worldCreation().resume();
    }

    @Inject(method = "lambda$confirmWorldCreation$0", at = @At("RETURN"))
    private static void cancelWorldCreation(Runnable create, Minecraft mc, CreateWorldScreen screen,
                                            boolean proceed, CallbackInfo ci) {
        if (!proceed) ((WorldOpenTransition.Creation)screen).mcagent$worldCreation().cancelled();
    }
}
