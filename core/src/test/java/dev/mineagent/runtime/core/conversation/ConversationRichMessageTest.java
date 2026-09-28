package dev.mineagent.runtime.core.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationRichMessageTest {
    @TempDir Path dir;
    final UUID world=UUID.randomUUID(), owner=UUID.randomUUID(), agent=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"),ZoneOffset.UTC);
    final ConversationRichMessage definition=new ConversationRichMessage("选择下一步", "#55FFFF", List.of(
            new ConversationRichMessage.Option("建屋顶", "confirm", "只修屋顶"),
            new ConversationRichMessage.Option("建阳台", "confirm", "只修阳台"),
            new ConversationRichMessage.Option("填入", "suggest", "/time query daytime\n保留中文"),
            new ConversationRichMessage.Option("复制", "copy", "literal `text`")));

    @Test void persistsStructuredActionsAlongsideStreamingReplyAndDeduplicatesPublication() throws Exception {
        UUID conversation, message, operation=UUID.randomUUID();
        try(var store=ConversationStore.open(dir.resolve("chat.db"),world,clock)) {
            var c=store.create(owner,agent,UUID.randomUUID(),"test");conversation=c.conversationId();
            var turn=store.begin(owner,agent,conversation,UUID.randomUUID(),c.revision(),"规划",0);
            var notice=store.publishRich(owner,agent,conversation,operation,definition);message=notice.messageId();
            assertEquals(message,store.publishRich(owner,agent,conversation,operation,definition).messageId());
            assertTrue(store.pending(turn.operationId()));
            assertEquals(3,store.get(owner,agent,conversation).messageCount());
            assertEquals("选择下一步",store.chunk(owner,agent,conversation,message,1,0,4096).text());
            assertEquals("1:OPEN",store.richSummaries(owner,agent,conversation,store.messages(owner,agent,conversation,0,20).messages()).get(message.toString()));
            assertThrows(IllegalArgumentException.class,()->store.publishRich(owner,agent,conversation,operation,new ConversationRichMessage("changed","#FFFFFF",List.of())));
        }
        try(var store=ConversationStore.open(dir.resolve("chat.db"),world,clock)) {
            assertEquals(definition,store.richMessage(owner,agent,conversation,message).definition());
            assertThrows(SecurityException.class,()->store.richMessage(UUID.randomUUID(),agent,conversation,message));
            assertThrows(SecurityException.class,()->store.richMessage(owner,UUID.randomUUID(),conversation,message));
            var other=store.create(owner,agent,UUID.randomUUID(),"other");
            assertThrows(SecurityException.class,()->store.richMessage(owner,agent,other.conversationId(),message));
        }
    }
    @Test void confirmationIsSingleUseAcrossRestartAndNeverReplaysUnknownDispatch() throws Exception {
        UUID c,id,selection;
        try(var store=ConversationStore.open(dir.resolve("choice.db"),world,clock)) {
            c=store.create(owner,agent,UUID.randomUUID(),"test").conversationId();
            id=store.publishRich(owner,agent,c,UUID.randomUUID(),definition).messageId();
            var claim=store.claimRich(owner,agent,c,id,0);selection=claim.message().selectionOperation();
            assertTrue(claim.dispatch());assertFalse(store.claimRich(owner,agent,c,id,0).dispatch());
            assertThrows(IllegalStateException.class,()->store.claimRich(owner,agent,c,id,1));
            assertThrows(IllegalArgumentException.class,()->store.claimRich(owner,agent,c,id,2));
        }
        try(var store=ConversationStore.open(dir.resolve("choice.db"),world,clock)) {
            var retry=store.claimRich(owner,agent,c,id,0);assertFalse(retry.dispatch());assertEquals(selection,retry.message().selectionOperation());
            assertEquals("CLAIMED",retry.message().state());
            store.finishRich(owner,agent,c,id,"UNKNOWN");assertFalse(store.claimRich(owner,agent,c,id,0).dispatch());
            assertEquals("UNKNOWN",store.richMessage(owner,agent,c,id).state());
        }
    }
    @Test void expiryAndArchiveDisableConfirmWithoutLosingCopyOrSuggestion() throws Exception {
        UUID c,id;
        try(var store=ConversationStore.open(dir.resolve("expiry.db"),world,clock)) {
            var conversation=store.create(owner,agent,UUID.randomUUID(),"test");c=conversation.conversationId();
            id=store.publishRich(owner,agent,c,UUID.randomUUID(),definition).messageId();
            store.change(owner,agent,c,UUID.randomUUID(),conversation.revision(),"archive","");
            assertEquals("READ_ONLY",store.richMessage(owner,agent,c,id).state());
            assertThrows(IllegalStateException.class,()->store.claimRich(owner,agent,c,id,0));
            var archived=store.get(owner,agent,c);store.change(owner,agent,c,UUID.randomUUID(),archived.revision(),"restore","");
        }
        try(var store=ConversationStore.open(dir.resolve("expiry.db"),world,Clock.offset(clock,Duration.ofMinutes(6)))) {
            assertEquals("EXPIRED",store.richMessage(owner,agent,c,id).state());
            assertThrows(IllegalStateException.class,()->store.claimRich(owner,agent,c,id,0));
            assertEquals("literal `text`",store.richMessage(owner,agent,c,id).definition().buttons().get(3).value());
        }
    }
}
