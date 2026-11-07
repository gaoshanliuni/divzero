package dev.mineagent.runtime.core.feedback;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import dev.mineagent.runtime.api.packages.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiFeedbackPolicyTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    static String declaration(String mode,String lifecycle) {
        return """
                {"schema":1,"entries":{"ui/index.html":{"events":{"help":{
                "mode":"%s","lifecycle":"%s","max_events":8,"cooldown_ms":1000,
                "fields":{"question":{"type":"string","max_length":100},"understood":{"type":"boolean"},"level":{"type":"number","minimum":0,"maximum":5}},
                "required":["question"]}}}}}
                """.formatted(mode,lifecycle);
    }
    static UiFeedbackPolicy policy() {return UiFeedbackPolicy.parse(declaration("RECORD_ONLY","INDEPENDENT").getBytes(StandardCharsets.UTF_8),"ui/index.html");}
    @Test void explicitFieldsOnlyAndCanonicalPayloadDoesNotChangeTypes() {
        var p=policy();assertEquals("RECORD_ONLY",p.event("help").mode());
        assertEquals("{\"level\":2,\"question\":\"help\",\"understood\":false}",p.validate("help","{\"understood\":false,\"question\":\"help\",\"level\":2}"));
        assertThrows(IllegalArgumentException.class,()->p.validate("help","{\"question\":\"help\",\"author\":\"forged\"}"));
        assertThrows(IllegalArgumentException.class,()->p.validate("help","{\"question\":\"help\",\"understood\":\"false\"}"));
    }
    @Test void missingRequiredUndeclaredEventNestedValuesAndLimitsReject() {
        var p=policy();
        for(String data:new String[]{"{}","[]","{\"question\":{}}","{\"question\":null}","{\"question\":\"help\",\"level\":6}","{\"question\":\""+"x".repeat(101)+"\"}"})assertThrows(IllegalArgumentException.class,()->p.validate("help",data));
        assertThrows(IllegalArgumentException.class,()->p.validate("approve_permission","{\"question\":\"yes\"}"));
    }
    @Test void duplicateKeysTrailingTokensAndPrototypeNamesReject() {
        var p=policy();assertThrows(IllegalArgumentException.class,()->p.validate("help","{\"question\":\"a\",\"question\":\"b\"}"));
        assertThrows(IllegalArgumentException.class,()->p.validate("help","{\"question\":\"a\"} {}"));
        for(String text:new String[]{declaration("RECORD_ONLY","INDEPENDENT").replace("\"schema\":1","\"schema\":1,\"schema\":1"),declaration("RECORD_ONLY","INDEPENDENT").replace("question","__proto__"),declaration("RECORD_ONLY","INDEPENDENT")+"{}"})assertThrows(IllegalArgumentException.class,()->UiFeedbackPolicy.parse(text.getBytes(StandardCharsets.UTF_8),"ui/index.html"));
    }
    @Test void everyModeAndLifecycleMustBeExplicitAndNoExecutableValidatorIsAllowed() {
        for(String mode:new String[]{"RECORD_ONLY","DETERMINISTIC","AGENT_WAKE"})assertEquals(mode,UiFeedbackPolicy.parse(declaration(mode,"PAGE_BOUND").getBytes(StandardCharsets.UTF_8),"ui/index.html").event("help").mode());
        for(String text:new String[]{declaration("EXECUTE_JS","INDEPENDENT"),declaration("RECORD_ONLY","AUTO"),declaration("RECORD_ONLY","INDEPENDENT").replace("\"max_events\":8","\"max_events\":8,\"validator\":\"return true\""),declaration("RECORD_ONLY","INDEPENDENT").replace("\"max_events\":8","\"max_events\":65")})assertThrows(IllegalArgumentException.class,()->UiFeedbackPolicy.parse(text.getBytes(StandardCharsets.UTF_8),"ui/index.html"));
    }
    @Test void declarationIsEntryScopedBoundedAndImmutable() {
        var p=policy();assertThrows(IllegalArgumentException.class,()->UiFeedbackPolicy.parse(declaration("RECORD_ONLY","INDEPENDENT").getBytes(StandardCharsets.UTF_8),"ui/other.html"));
        assertThrows(IllegalArgumentException.class,()->UiFeedbackPolicy.parse(new byte[32769],"ui/index.html"));
        assertThrows(UnsupportedOperationException.class,()->p.events().clear());assertThrows(UnsupportedOperationException.class,()->p.event("help").fields().clear());
        assertEquals(64,p.sha256().length());assertNotEquals(p.sha256(),UiFeedbackPolicy.parse(declaration("RECORD_ONLY","PAGE_BOUND").getBytes(StandardCharsets.UTF_8),"ui/index.html").sha256());
    }
    @Test void manifestResourcePinsAreCheckedBeforeAnyDeclarationCanBeUsed()throws Exception{
        byte[] bytes=declaration("RECORD_ONLY","INDEPENDENT").getBytes(StandardCharsets.UTF_8);
        String hash=ResourceIds.create();
        var ref=new RuntimeResourceRef(UiFeedbackPolicy.RESOURCE,hash,RuntimeResourceSide.CLIENT,"application/json",bytes.length);
        var entry=new RuntimeEntrypoint("ui/index.html",RuntimeResourceSide.CLIENT,"a".repeat(64));
        var html=new RuntimeResourceRef(entry.path(),entry.sha256(),RuntimeResourceSide.CLIENT,"text/html",1);
        var pkg=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,"Feedback test","1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("ui",entry),Map.of(),Map.of(UiFeedbackPolicy.RESOURCE,ref,entry.path(),html),PackageOrigin.LOCAL_STUDIO,false,1,"b".repeat(64),"TEST_SIGNATURE_NOT_VALIDATED_BY_POLICY_PARSER",0);
        try(var signer=dev.mineagent.runtime.core.crypto.IdentitySigner.open(root.resolve("signer"))){
        var signed=dev.mineagent.runtime.core.packages.PackageSignatures.signNew(pkg,h->h.equals(hash)?bytes:new byte[]{1},signer);
        assertEquals(hash,UiFeedbackPolicy.fromPackage(signed,"ui/index.html",h->bytes).sha256());
        assertThrows(IllegalArgumentException.class,()->UiFeedbackPolicy.fromPackage(signed,"ui/else.html",h->bytes));
        assertThrows(SecurityException.class,()->UiFeedbackPolicy.fromPackage(signed,"ui/index.html",h->new byte[bytes.length]));
        assertThrows(IllegalArgumentException.class,()->UiFeedbackPolicy.fromPackage(signed,"ui/index.html",h->new byte[1]));
        }
    }
}
