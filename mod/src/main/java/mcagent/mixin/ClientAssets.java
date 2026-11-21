package mcagent.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ClientPackSource.class)
abstract class ClientAssets {
    // Loader alone does not register mod translations as game resources.
    @Redirect(method = "createVanillaPackSource", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/packs/VanillaPackResourcesBuilder;pushJarResources()Lnet/minecraft/server/packs/VanillaPackResourcesBuilder;"))
    private static VanillaPackResourcesBuilder addAgentAssets(VanillaPackResourcesBuilder pack) {
        var mod = FabricLoader.getInstance().getModContainer("minecraft_agent").orElseThrow();
        return pack.pushJarResources().exposeNamespace("minecraft_agent")
            .pushAssetPath(PackType.CLIENT_RESOURCES, mod.findPath("assets").orElseThrow());
    }
}
