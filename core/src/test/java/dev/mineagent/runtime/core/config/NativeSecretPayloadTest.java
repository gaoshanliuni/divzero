package dev.mineagent.runtime.core.config;
import dev.mineagent.runtime.core.crypto.SecretChannel;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class NativeSecretPayloadTest {
    @Test void encryptedKeyCannotBeReboundToAnotherRevisionOrServerInstance()throws Exception{
        var keys=SecretChannel.generateServerKeyPair();var p=new NativeSecretPayload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,"test-key-not-real");
        var envelope=SecretChannel.seal(keys.getPublic(),p.encode());var clear=SecretChannel.open(keys.getPrivate(),envelope);
        assertEquals(p,NativeSecretPayload.decode(clear,p.operation(),p.world(),p.instance(),3));
        assertThrows(IllegalArgumentException.class,()->NativeSecretPayload.decode(clear,p.operation(),p.world(),p.instance(),4));
        assertThrows(IllegalArgumentException.class,()->NativeSecretPayload.decode(clear,p.operation(),p.world(),UUID.randomUUID(),3));
        assertThrows(IllegalArgumentException.class,()->NativeSecretPayload.decode(clear,UUID.randomUUID(),p.world(),p.instance(),3));assertFalse(p.toString().contains(p.value()));
    }
}
