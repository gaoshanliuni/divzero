package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import dev.latvian.mods.kubejs.script.*;
import dev.mineagent.runtime.core.ui.dynamic.InterfaceDefinition;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** KubeJS owns widget construction/event binding; DivZero owns data, revisions and authority. */
public final class KubeInterfaceRenderer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private KubeInterfaceRenderer(){}
    public static LdInterfaceRenderer.Rendered build(InterfaceDefinition definition,Map<String,JsonNode> data,LdInterfaceRenderer.Events events) throws Exception {
        if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("INTERFACE_CLIENT_THREAD_REQUIRED");
        String program;
        try(var stream=KubeInterfaceRenderer.class.getResourceAsStream("/assets/mineagent_runtime/nativeui/build.js")){
            if(stream==null)throw new IllegalStateException("INTERFACE_BUILDER_MISSING");program=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        // No reload(), global event registration, user script directory or shared KubeJS globals.
        var manager=new ScriptManager(ScriptType.CLIENT);
        var factory=new KubeJSContextFactory(manager);manager.contextFactory=factory;
        var context=(KubeJSContext)factory.enter();
        var bridge=new LdInterfaceRenderer.BuilderBridge(definition,data,events);
        var bindings=new BindingRegistry(context,context.topLevelScope);
        bindings.add("bridge",bridge);
        bindings.add("definitionJson",JSON.createObjectNode().set("root",definition.root()).toString());
        try {
            context.evaluateString(context.topLevelScope,program,"divzero/nativeui/build.js",1,null);
            if(bridge.result()==null)throw new IllegalStateException("INTERFACE_BUILDER_NO_RESULT");
            return bridge.result();
        }catch(Exception failure){if(bridge.result()!=null)bridge.result().close();throw failure;}
    }

}
