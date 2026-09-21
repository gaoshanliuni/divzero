package dev.mineagent.runtime.core.agent;
import dev.mineagent.runtime.api.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentPersonaServiceTest {
    @TempDir Path dir;
    final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),collaborator=UUID.randomUUID(),other=UUID.randomUUID();
    final AgentDefinition a=new AgentDefinition(UUID.randomUUID(),"建筑师","personaA",owner,AgentMode.CREATOR,Set.of(collaborator));
    final AgentDefinition b=new AgentDefinition(UUID.randomUUID(),"吟游诗人","personaB",owner,AgentMode.CREATOR,Set.of());
    final Clock clock=Clock.fixed(Instant.ofEpochMilli(12345),ZoneOffset.UTC);
    @Test void independentFreeTextPersistsAcrossRestartWithoutChangingDefinitions()throws Exception{
        String text="  身份：严谨建筑师\n性格：温和\n与玩家：搭档\n请用中文角色扮演。  ";
        try(var service=AgentPersonaService.open(dir.resolve("runtime.db"),world,clock)){
            assertEquals(0,service.read(a,owner,false).revision());
            var result=service.save(a,owner,false,UUID.randomUUID(),0,text);assertTrue(result.accepted());assertEquals(text,result.persona().text());
            assertEquals("",service.read(b,owner,false).text());assertEquals(AgentMode.CREATOR,a.mode());
        }
        try(var service=AgentPersonaService.open(dir.resolve("runtime.db"),world,clock)){
            assertEquals(text,service.read(a,collaborator,false).text());assertEquals(1,service.read(a,owner,false).revision());
            assertEquals("",service.read(b,owner,false).text());
        }
        try(var otherWorld=AgentPersonaService.open(dir.resolve("runtime.db"),UUID.randomUUID(),clock)){assertEquals("",otherWorld.read(a,owner,false).text());}
    }
    @Test void currentAuthorityIsCheckedBeforeReplayingAnySavedReceipt()throws Exception{
        UUID op=UUID.randomUUID();try(var service=AgentPersonaService.open(dir.resolve("runtime.db"),world,clock)){
            assertThrows(SecurityException.class,()->service.read(a,other,false));
            assertThrows(SecurityException.class,()->service.read(a,other,true));
            assertThrows(SecurityException.class,()->service.save(a,other,true,UUID.randomUUID(),0,"OP is not persona ownership"));
            assertThrows(SecurityException.class,()->service.save(a,other,false,op,0,"bad"));
            assertTrue(service.save(a,collaborator,false,op,0,"一个独立人设").accepted());
            var revoked=a.withCollaborators(Set.of());assertThrows(SecurityException.class,()->service.save(revoked,collaborator,false,op,0,"一个独立人设"));
            assertEquals(1,service.read(a,owner,false).revision());
            assertThrows(SecurityException.class,()->service.read(null,owner,true));
        }
    }
    @Test void casIdempotenceAndResetAreDurableAndDoNotRestoreStaleText()throws Exception{
        UUID first=UUID.randomUUID();try(var service=AgentPersonaService.open(dir.resolve("runtime.db"),world,clock)){
            var applied=service.save(a,owner,false,first,0,"严谨");assertTrue(applied.accepted());
            var duplicate=service.save(a,owner,false,first,0,"严谨");assertTrue(duplicate.duplicate());assertEquals(1,duplicate.persona().revision());
            assertEquals("OPERATION_ID_REUSED",service.save(b,owner,false,first,0,"不同AI").code());
            assertEquals("OPERATION_ID_REUSED",service.save(a,owner,false,first,1,"新内容").code());
            assertEquals("STALE_REVISION",service.save(a,collaborator,false,UUID.randomUUID(),0,"过期草稿").code());
            assertEquals("",service.save(a,owner,false,UUID.randomUUID(),1,"").persona().text());
        }
        try(var service=AgentPersonaService.open(dir.resolve("runtime.db"),world,clock)){
            var old=service.save(a,owner,false,first,0,"严谨");assertTrue(old.duplicate());assertEquals(2,old.persona().revision());assertEquals("",old.persona().text());assertEquals(1,old.appliedRevision());
        }
    }
    @Test void explicitLengthAndRevisionErrorsNeverSilentlyTruncateText()throws Exception{
        try(var service=AgentPersonaService.open(dir.resolve("runtime.db"),world,clock)){
            assertThrows(IllegalArgumentException.class,()->service.save(a,owner,false,UUID.randomUUID(),0,"x".repeat(8193)));
            assertThrows(IllegalArgumentException.class,()->service.save(a,owner,false,UUID.randomUUID(),-1,"hello"));
            assertEquals(0,service.read(a,owner,false).revision());
        }
    }
}
