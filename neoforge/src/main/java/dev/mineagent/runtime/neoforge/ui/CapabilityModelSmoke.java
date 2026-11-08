package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual two-texture item and non-solid visual, with a fixed, signed candidate. */
public final class CapabilityModelSmoke {
    public static Map<String,Object> publish(ServerPlayer player,UUID agent)throws Exception{
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!"capability_models".equals(System.getProperty("mineagent.skillSmokeMode")))throw new SecurityException("FIXTURE_DISABLED");
        String source="""
            {"version":3,"materials":{"red":"models/red.png","blue":"models/blue.png"},
             "boxes":[{"from":[-0.2,0,-0.1],"to":[0,0.4,0.1],"color":"#ffffff","material":"red"},
                      {"from":[0,0,-0.1],"to":[0.2,0.4,0.1],"color":"#ffffff","material":"blue"}],
             "itemTransform":{"translation":[0.1,0.1,0],"rotation":[0,20,10],"scale":[1.2,1.2,1.2]}}
            """;
        var files=new ArrayList<GeneratedFile>();files.add(file("server/main.js",RuntimeResourceSide.SERVER,"application/javascript","on('instance.create',function(){content.giveItem('material','models/item.json',1,'双材质验收');content.createObject('visual','models/item.json',4,1,4);});".getBytes(StandardCharsets.UTF_8)));
        files.add(file("models/item.json",RuntimeResourceSide.COMMON,"application/json",source.getBytes(StandardCharsets.UTF_8)));
        for(var color:Map.of("red",0xffff0000,"blue",0xff0000ff).entrySet()){var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int x=0;x<2;x++)for(int y=0;y<2;y++)image.setRGB(x,y,color.getValue());var out=new java.io.ByteArrayOutputStream();if(!javax.imageio.ImageIO.write(image,"png",out))throw new IllegalStateException("FIXTURE_PNG_WRITER");files.add(file("models/"+color.getKey()+".png",RuntimeResourceSide.COMMON,"image/png",out.toByteArray()));}
        var code=files.getFirst();var env=dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe();var contract=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target(env.minecraft(),env.loader(),env.loaderVersion(),env.namespace(),env.javaFeature(),Map.of())));
        var definition=new RuntimeDefinition(UUID.randomUUID(),"双纹理和静态视觉",RuntimeDefinitionKind.ENTITY,"server",Set.of("models/item.json","models/red.png","models/blue.png"),Map.of(),1);
        return CapabilityDeliverySmoke.publishParsed(player,agent,new ParsedRuntimePackage("双纹理物品验收","1.0",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),Map.of("server",new RuntimeEntrypoint(code.path(),code.side(),code.sha256())),List.of(definition),files,contract));
    }
    private static GeneratedFile file(String path,RuntimeResourceSide side,String media,byte[] content){return new GeneratedFile(path,side,media,ResourceIds.create(),content);}
    private CapabilityModelSmoke(){}
}
