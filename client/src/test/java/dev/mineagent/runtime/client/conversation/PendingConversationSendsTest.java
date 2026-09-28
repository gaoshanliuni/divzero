package dev.mineagent.runtime.client.conversation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PendingConversationSendsTest {
    @Test void unknownSendKeepsExactOperationAcrossReloadAndRevisionChange(){var state=new PendingConversationSends();var agent=UUID.randomUUID();var conversation=UUID.randomUUID();var first=state.prepare(agent,conversation,3,"hello",null);var restored=new PendingConversationSends();restored.restore(state.snapshot());assertEquals(first,restored.prepare(agent,conversation,4,"hello",null));restored.accepted(first.operation());assertNotEquals(first.operation(),restored.prepare(agent,conversation,4,"hello",null).operation());}
    @Test void anOldReceiptDoesNotRetireANewerDraft(){var state=new PendingConversationSends();var agent=UUID.randomUUID();var c=UUID.randomUUID();var old=state.prepare(agent,c,1,"first",null);var next=state.prepare(agent,c,1,"second",null);state.accepted(old.operation());assertEquals(List.of(next),state.snapshot());}
    @Test void independentConversationsDoNotShareOperations(){var state=new PendingConversationSends();var agent=UUID.randomUUID();assertNotEquals(state.prepare(agent,UUID.randomUUID(),1,"same",null).operation(),state.prepare(agent,UUID.randomUUID(),1,"same",null).operation());}
}
