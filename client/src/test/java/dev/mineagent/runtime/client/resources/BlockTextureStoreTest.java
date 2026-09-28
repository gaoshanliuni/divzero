package dev.mineagent.runtime.client.resources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class BlockTextureStoreTest {
    @TempDir Path dir;
    @Test void scopeCasHashesAndRestoreArePersistent()throws Exception{
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"PNG",bytes);var png=bytes.toByteArray();String hash=BlockTextureStore.hash(png);var texture=new BlockTextureStore.Texture(hash,"https://example.org/texture.png",16);var store=new BlockTextureStore(dir);
        var first=new BlockTextureStore.State(1,Map.of("minecraft:block/stone",texture));store.save("world-one",0,first,Map.of("minecraft:block/stone",png));
        assertEquals(first,new BlockTextureStore(dir).load("world-one"));assertArrayEquals(png,store.images("world-one",first).get("minecraft:block/stone"));assertEquals(0,store.load("world-two").revision());
        assertThrows(IllegalStateException.class,()->store.save("world-one",0,first,Map.of("minecraft:block/stone",png)));
        store.save("world-one",1,new BlockTextureStore.State(2,Map.of()),Map.of());assertTrue(store.load("world-one").textures().isEmpty());
        assertThrows(IllegalArgumentException.class,()->BlockTextureStore.resource("minecraft:../../config"));assertThrows(IllegalArgumentException.class,()->BlockTextureStore.png(png,512));
    }
}
