package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.chat.AiChatMessages;
import net.minecraft.network.chat.*;import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class AiNameHoverTest {
 @Test void decoratesOnlyTaggedNameWithoutVisiblePrefixOrChangingClicks(){
  var click=new ClickEvent.RunCommand("/ai msg limit 1024");var message=AiChatMessages.line("星河"," 你好").append(Component.literal(" [确认]").withStyle(s->s.withClickEvent(click)));
  var result=AiNameHover.apply(message,"2026-09-25 13:06:07");assertEquals("[星河] 你好 [确认]",result.getString());assertEquals(message.getString(),result.getString());
  assertNull(message.getSiblings().getFirst().getStyle().getHoverEvent());assertNull(result.getSiblings().get(1).getStyle().getHoverEvent());
  assertEquals(click,result.getSiblings().get(2).getStyle().getClickEvent());var hover=(HoverEvent.ShowText)result.getSiblings().getFirst().getStyle().getHoverEvent();assertEquals("2026-09-25 13:06:07",hover.value().getString());
 }
 @Test void nestedThinkingRemainsTaggedAndBodyNamesDoNotBecomeHoverTargets(){
  var thinking=Component.translatableWithFallback("mineagent.chat.thinking","%s[思考]%s",AiChatMessages.name("星河"),"分析 [其它AI]");var shown=AiNameHover.apply(thinking,"DATE");
  assertEquals(thinking.getString(),shown.getString());var contents=(TranslatableContents)shown.getContents();assertEquals("mineagent.chat.thinking",contents.getKey());assertInstanceOf(HoverEvent.ShowText.class,((Component)contents.getArgs()[0]).getStyle().getHoverEvent());
  var plain=Component.literal("[星河] 玩家自己的文字");assertNull(AiNameHover.apply(plain,"DATE").getStyle().getHoverEvent());assertSame(thinking,AiNameHover.apply(thinking,""));
 }
}
