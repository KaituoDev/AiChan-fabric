package fun.kaituo.aichanfabric.mixin;

import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLoginNetworkHandler.class)
public interface ServerLoginNetworkHandlerAccessor {
    @Accessor("profileName")
    String getProfileName();
}
