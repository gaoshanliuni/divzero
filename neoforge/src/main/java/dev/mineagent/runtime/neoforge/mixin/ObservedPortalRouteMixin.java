package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.skill.PortalRouteObservations;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class ObservedPortalRouteMixin {
    @Unique private PortalRouteObservations.Capture divzero$portalCapture;
    @Inject(method="handlePortal",at=@At("HEAD"))
    private void divzero$beforePortal(CallbackInfo callback){try{divzero$portalCapture=PortalRouteObservations.before((Entity)(Object)this);}catch(RuntimeException unsupported){divzero$portalCapture=null;}}
    @Inject(method="handlePortal",at=@At("RETURN"))
    private void divzero$afterPortal(CallbackInfo callback){var capture=divzero$portalCapture;divzero$portalCapture=null;try{PortalRouteObservations.after((Entity)(Object)this,capture);}catch(RuntimeException unsupported){/* Observation must not prevent native travel. */}}
}
