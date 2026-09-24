package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.chat.AiChatMessages;
import net.minecraft.network.chat.*;import net.minecraft.network.chat.contents.TranslatableContents;
/** Copy presentation components only; never mutate the signed/stored message or annotate body text. */
public final class AiNameHover {
    public static Component apply(Component original,String tooltip){
        if(tooltip.isEmpty())return original;
        return copy(original,tooltip);
    }
    private static MutableComponent copy(Component original,String tooltip){
        var contents=original.getContents();MutableComponent result;
        if(contents instanceof TranslatableContents t){var args=t.getArgs().clone();for(int i=0;i<args.length;i++)if(args[i] instanceof Component component)args[i]=copy(component,tooltip);result=Component.translatableWithFallback(t.getKey(),t.getFallback(),args);}
        else result=original.plainCopy();
        result.setStyle(original.getStyle());
        if(contents instanceof TranslatableContents t&&t.getKey().equals(AiChatMessages.NAME_KEY)){
            var date=Component.literal(tooltip).withStyle(style->style.withColor(0xFF99CC));
            if(original.getStyle().getHoverEvent() instanceof HoverEvent.ShowText existing)date=Component.empty().append(existing.value()).append("\n").append(date);
            var hover=date;result.withStyle(style->style.withHoverEvent(new HoverEvent.ShowText(hover)));
        }
        for(var sibling:original.getSiblings())result.append(copy(sibling,tooltip));return result;
    }
    private AiNameHover(){}
}
