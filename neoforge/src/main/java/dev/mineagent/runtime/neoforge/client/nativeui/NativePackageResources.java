package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.texture.*;
import dev.mineagent.runtime.client.webui.PackageUiResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Assets are supplied only to their authorized view. Unrelated private dynamic texture IDs are not resolvable. */
final class NativePackageResources implements AutoCloseable {
    private final Map<String,IGuiTexture> textures=new HashMap<>();
    private final List<Identifier> registered=new ArrayList<>();
    NativePackageResources(Map<String,PackageUiResolver.Asset> assets,Set<String> requested)throws Exception {
        String scope=UUID.randomUUID().toString().replace("-","");
        try{
            for(String resource:requested){
                if(!resource.startsWith("package:"))continue;String path=resource.substring("package:".length());var asset=assets.get(path);
                if(asset==null||!Set.of("image/png","image/jpeg").contains(asset.mediaType())||asset.size()>8*1024*1024)throw new IllegalArgumentException("NATIVE_PACKAGE_IMAGE_RESOURCE: "+path);
                // Check header dimensions before allocating a decoded native image.
                try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(asset.bytes()))){
                    var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("NATIVE_PACKAGE_IMAGE_FORMAT");var reader=readers.next();
                    try{reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||(long)width*height>4_194_304)throw new IllegalArgumentException("NATIVE_PACKAGE_IMAGE_DIMENSIONS");}finally{reader.dispose();}
                }
                var image=com.mojang.blaze3d.platform.NativeImage.read(asset.bytes());
                if(image.getWidth()<1||image.getHeight()<1||(long)image.getWidth()*image.getHeight()>4_194_304){image.close();throw new IllegalArgumentException("NATIVE_PACKAGE_IMAGE_DIMENSIONS");}
                var id=Identifier.fromNamespaceAndPath("mineagent_runtime","native_package/"+scope+"/"+registered.size());Minecraft.getInstance().getTextureManager().register(id,new DynamicTexture(()->"DivZero signed UI image",image));registered.add(id);textures.put(resource,SpriteTexture.of(id));
            }
        }catch(Exception failure){close();throw failure;}
    }
    Map<String,IGuiTexture> textures(){return Map.copyOf(textures);}
    @Override public void close(){for(var texture:registered)Minecraft.getInstance().getTextureManager().release(texture);registered.clear();textures.clear();}
}
