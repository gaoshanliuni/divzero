package dev.mineagent.runtime.core.objects;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeMaterialsTest {
    private static final String SOURCE="""
        {"version":3,"materials":{"red":"textures/red.png","blue":"textures/blue.png"},
         "boxes":[{"from":[-0.2,0,-0.1],"to":[0,0.4,0.1],"color":"#ffffff","material":"red"},
                  {"from":[0,0,-0.1],"to":[0.2,0.4,0.1],"color":"#ffffff","material":"blue"}],
         "itemTransform":{"translation":[0.1,0.1,0],"rotation":[0,20,10],"scale":[1.2,1.2,1.2]}}
        """;
    private static byte[] png(int color)throws Exception{var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int x=0;x<2;x++)for(int y=0;y<2;y++)image.setRGB(x,y,color);var out=new java.io.ByteArrayOutputStream();assertTrue(javax.imageio.ImageIO.write(image,"png",out));return out.toByteArray();}
    @Test void twoMaterialsKeepTheirOwnTextureCoordinatesAndTransform()throws Exception{
        var mesh=RuntimeMesh.parse(SOURCE);assertEquals(24,mesh.triangles().size());assertEquals(2,mesh.batches().size());assertEquals(12,mesh.batches().get("textures/red.png").size());assertTrue(mesh.collision().nonSolid());assertEquals(List.of(0d,20d,10d),mesh.itemTransform().rotation());
        var textures=Map.of("textures/red.png",png(0xffff0000),"textures/blue.png",png(0xff0000ff));
        var bundle=RuntimeModelBundle.create(SOURCE.getBytes(StandardCharsets.UTF_8),textures);var received=RuntimeModelBundle.decode(bundle.bytes(),bundle.sha256());assertEquals(SOURCE,received.modelSource());assertEquals(mesh,received.mesh());
        for(var texture:textures.entrySet())assertArrayEquals(texture.getValue(),received.textures().get(texture.getKey()));
        var binding=RuntimeItemBinding.create(UUID.randomUUID(),UUID.randomUUID(),"piece","a".repeat(64),"models/item.json",SOURCE);assertEquals(2,RuntimeItemBinding.parse(binding.encode()).mesh().materials().size());
    }
    @Test void undeclaredMissingAndTrailingMaterialInputsAreRejected()throws Exception{
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(SOURCE.replace("\"material\":\"red\"","\"material\":\"unknown\"")));
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(SOURCE.replace("textures/red.png","../red.png")));
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(SOURCE.replace("\"version\":3","\"version\":2")));
        assertThrows(IllegalArgumentException.class,()->RuntimeModelBundle.create(SOURCE.getBytes(StandardCharsets.UTF_8),Map.of("textures/red.png",png(0xffff0000))));
        var bundle=RuntimeModelBundle.create(SOURCE.getBytes(StandardCharsets.UTF_8),Map.of("textures/red.png",png(0xffff0000),"textures/blue.png",png(0xff0000ff)));
        assertThrows(IllegalArgumentException.class,()->RuntimeModelBundle.decode(Arrays.copyOf(bundle.bytes(),bundle.size()+1),bundle.sha256()));
        var copy=bundle.textures();copy.get("textures/red.png")[0]=0;assertEquals((byte)137,bundle.textures().get("textures/red.png")[0]);
    }
}
