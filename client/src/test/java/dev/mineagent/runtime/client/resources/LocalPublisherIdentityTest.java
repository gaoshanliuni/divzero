package dev.mineagent.runtime.client.resources;

import dev.mineagent.runtime.client.scripts.LocalClientScriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LocalPublisherIdentityTest {
    @TempDir Path root;
    @Test void sharesAnExplicitPublisherAcrossClientResourceAndScriptStoresAndReopens()throws Exception{
        String key="a".repeat(64),id;
        try(var resources=LocalResourcePackStore.get(root);var scripts=LocalClientScriptStore.get(root)){
            id=resources.publisher("local-integrated",key);
            assertEquals(id,scripts.publisher("local-integrated",key));
            assertNotEquals(id,resources.publisher("other.example",key));
            assertNotEquals(id,resources.publisher("local-integrated","b".repeat(64)));
            assertThrows(IllegalArgumentException.class,()->scripts.publisher("",key));
        }
        try(var resources=LocalResourcePackStore.get(root);var scripts=LocalClientScriptStore.get(root)){
            assertEquals(id,resources.publisher("local-integrated",key));
            assertEquals(id,scripts.publisher("local-integrated",key));
        }
    }
}
