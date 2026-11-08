package dev.mineagent.runtime.neoforge.client.objects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.mineagent.runtime.core.creature.CreatureAnimation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.util.*;

/** Installed adapter fixture, exercised by the real EntityRenderDispatcher Mixin. */
public final class RendererAdapterSmoke {
    private static final String ID="capability_fixture:controller";
    private static AutoCloseable registration;private static int captured,restored,applied;private static double controllerAngle;private static boolean injectFailure,failed;
    public static void start(UUID target){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!"capability_adapters".equals(System.getProperty("mineagent.skillSmokeMode")))throw new SecurityException("FIXTURE_DISABLED");
        captured=restored=applied=0;controllerAngle=0;injectFailure=failed=false;
        registration=RendererAdapters.register(new RendererAdapters.Adapter(){
            public String id(){return ID;}public String version(){return "1";}
            public boolean supports(EntityRenderer<?,?> renderer,EntityRenderState state){var meta=state.getRenderData(EntityVisualClient.KEY);return meta!=null&&meta.entity().equals(target);}
            public RendererAdapters.Frame capture(EntityRenderer<?,?> renderer,EntityRenderState state,PoseStack poses){
                captured++;double before=controllerAngle;
                return new RendererAdapters.Frame(){
                    public void apply(List<RendererAdapters.Track> tracks){
                        for(var track:tracks){var clip=track.specification().parts().get("controller/rotor");if(clip!=null){controllerAngle=CreatureAnimation.sample(clip,track.time())[4];poses.mulPose(Axis.YP.rotationDegrees((float)controllerAngle));if(Math.abs(controllerAngle-30)<.001)applied++;}}
                        if(injectFailure){controllerAngle=85;failed=true;throw new IllegalStateException("INJECTED_ADAPTER_APPLY_FAILURE");}
                    }
                    public Map<String,Object> inspect(int offset,int limit){return Map.of("parts",List.of(Map.of("name","controller/rotor","angle",controllerAngle)),"nextOffset",-1);}
                    public void close(){controllerAngle=before;restored++;}
                };
            }
        });
    }
    public static boolean applied(){return applied>=3&&captured==restored&&controllerAngle==0;}
    public static void failNext(){injectFailure=true;}
    public static boolean failureRestored(){return failed&&captured==restored&&controllerAngle==0&&RendererAdapters.catalog().stream().anyMatch(a->a.get("id").equals(ID)&&!a.get("error").isEmpty());}
    public static Map<String,Object> finish()throws Exception{registration.close();if(RendererAdapters.catalog().stream().anyMatch(a->a.get("id").equals(ID)))throw new IllegalStateException("ADAPTER_UNREGISTER_FAILED");return Map.of("captureFrames",captured,"restoreFrames",restored,"nativeControllerApplications",applied,"failureRestored",failed&&controllerAngle==0,"unregistered",true,"fixtureOnly",true);}
    private RendererAdapterSmoke(){}
}
