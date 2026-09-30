package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationFocusRegistryTest {
    final UUID viewer=UUID.randomUUID(),agent=UUID.randomUUID();
    ConversationStore.Conversation c(UUID id){return new ConversationStore.Conversation(id,viewer,agent,"C",1,"ACTIVE",1,0,"",0,0);}
    @Test void selectionIsExplicitAndDoesNotRouteNativeChatUntilOptedIn(){
        var r=new ConversationFocusRegistry(Clock.systemUTC());var connection=new Object();assertTrue(r.current(viewer,connection).isEmpty());
        var a=r.select(connection,c(UUID.randomUUID()),UUID.randomUUID(),60000);assertFalse(a.nativeInput());assertEquals(a,r.current(viewer,connection).orElseThrow());
        r.nativeInput(viewer,connection,a.contextId(),true);assertTrue(r.current(viewer,connection).orElseThrow().nativeInput());
        assertTrue(r.current(viewer,new Object()).isEmpty());
    }
    @Test void staleSelectionAndCloseCannotResurrectOrClearANewerConversation(){
        var r=new ConversationFocusRegistry(Clock.systemUTC());var connection=new Object();var ca=c(UUID.randomUUID());var a=r.select(connection,ca,UUID.randomUUID(),60000);var b=r.select(connection,c(UUID.randomUUID()),UUID.randomUUID(),60000);
        assertFalse(a.current());assertTrue(b.current());r.clear(viewer,connection,a.contextId());assertTrue(b.current());
        assertThrows(IllegalStateException.class,()->r.select(connection,ca,a.contextId(),60000));
        assertThrows(IllegalStateException.class,()->r.nativeInput(viewer,connection,a.contextId(),true));
        r.clear(viewer,connection,b.contextId());assertFalse(b.current());assertTrue(r.current(viewer,connection).isEmpty());
    }
    @Test void expiryAndDisconnectRevokeTheOriginalConnection(){
        var now=new java.util.concurrent.atomic.AtomicLong(10);Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        var r=new ConversationFocusRegistry(clock);var old=new Object();var a=r.select(old,c(UUID.randomUUID()),UUID.randomUUID(),100);now.set(110);assertTrue(r.current(viewer,old).isEmpty());assertFalse(a.current());
        var next=new Object();var b=r.select(next,c(UUID.randomUUID()),UUID.randomUUID(),100);r.disconnect(viewer,old);assertTrue(b.current());r.disconnect(viewer,next);assertFalse(b.current());
    }
    @Test void returningToAConversationRequiresANewSelectionIdentity(){
        var r=new ConversationFocusRegistry(Clock.systemUTC());var connection=new Object();var ca=c(UUID.randomUUID());var cb=c(UUID.randomUUID());
        var first=r.select(connection,ca,UUID.randomUUID(),60000);r.clear(viewer,connection,first.contextId());
        var second=r.select(connection,cb,UUID.randomUUID(),60000);r.clear(viewer,connection,second.contextId());
        var back=r.select(connection,ca,UUID.randomUUID(),60000);r.nativeInput(viewer,connection,back.contextId(),true);
        assertTrue(back.nativeInput());assertFalse(first.current());assertFalse(second.current());
        r.clear(viewer,connection,first.contextId());assertSame(back,r.current(viewer,connection).orElseThrow());
        assertThrows(IllegalStateException.class,()->r.select(connection,ca,first.contextId(),60000));
        assertSame(back,r.current(viewer,connection).orElseThrow());
    }
    @Test void sameSelectionRenewalKeepsAudioAndRoutingButExpiredIdsCannotRevive(){
        var now=new java.util.concurrent.atomic.AtomicLong(0);Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        var r=new ConversationFocusRegistry(clock);var connection=new Object();var ca=c(UUID.randomUUID());var first=r.select(connection,ca,UUID.randomUUID(),100);
        r.nativeInput(viewer,connection,first.contextId(),true);now.set(90);assertSame(first,r.select(connection,ca,first.contextId(),100));now.set(150);assertTrue(first.current());assertTrue(first.nativeInput());
        now.set(200);assertFalse(first.current());assertThrows(IllegalStateException.class,()->r.select(connection,ca,first.contextId(),100));
        var fresh=r.select(connection,ca,UUID.randomUUID(),100);assertNotSame(first,fresh);assertFalse(fresh.nativeInput());
    }
}
