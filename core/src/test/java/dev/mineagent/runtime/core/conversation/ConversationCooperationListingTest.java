package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationCooperationListingTest {
 @TempDir Path root;
 @Test void sharedThreadIsVisibleToBothAgentsAndManualSenderIsNotImpersonated()throws Exception{
  var owner=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
  try(var store=ConversationStore.open(root.resolve("db"),UUID.randomUUID(),Clock.systemUTC())){
   store.create(owner,a,UUID.randomUUID(),"普通聊天");var peer=store.peerThread(owner,a,b,"A","B");
   assertEquals(2,store.listIncludingPeers(owner,a,"ACTIVE","",0,20).conversations().size());assertEquals(1,store.list(owner,a,"ACTIVE","",0,20).conversations().size());
   assertEquals(peer.conversationId(),store.listIncludingPeers(owner,b,"ACTIVE","",0,20).conversations().getFirst().conversationId());assertTrue(store.listIncludingPeers(UUID.randomUUID(),a,"ALL","",0,20).conversations().isEmpty());
   var c=store.get(owner,b,peer.conversationId());var turn=store.begin(owner,b,c.conversationId(),UUID.randomUUID(),c.revision(),"玩家手动补充：保留北门",0,null,null,null,new ConversationStore.Author("PLAYER",owner,"玩家"),new ConversationStore.Author("AI",b,"B"));
   store.finish(turn.operationId(),"COMPLETE","收到","");var user=store.message(owner,b,c.conversationId(),turn.userMessageId());assertEquals("PLAYER",store.author(owner,b,c.conversationId(),user).kind());assertEquals(owner,store.author(owner,b,c.conversationId(),user).senderId());
   assertThrows(IllegalStateException.class,()->store.author(owner,b,c.conversationId(),user.messageId(),new ConversationStore.Author("AI",a,"A")));
   assertThrows(SecurityException.class,()->store.author(UUID.randomUUID(),b,c.conversationId(),user));
  }
 }
}
