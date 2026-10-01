package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Small local coding handbook plus a searchable official documentation directory. Never a prompt authority. */
public final class NativeUiWiki {
    public record Page(String id,String title,String keywords,String url,String content){}
    private static final List<Page> PAGES=load();
    private static List<Page> load(){try(var in=NativeUiWiki.class.getResourceAsStream("/dev/mineagent/runtime/ui-wiki.json")){
        if(in==null)throw new IllegalStateException("UI_WIKI_MISSING");return List.of(new ObjectMapper().readValue(in,Page[].class));
    }catch(java.io.IOException e){throw new IllegalStateException("UI_WIKI_INVALID",e);}}
    public static Page page(String id){return PAGES.stream().filter(p->p.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("UI_WIKI_TOPIC_NOT_FOUND: "+id));}
    public static Map<String,Object> read(String topic,String query,int offset,int length){
        if(offset<0||length<1||length>12000)throw new IllegalArgumentException("UI_WIKI_PAGE_RANGE");
        if(topic==null||topic.isBlank()){
            var words=Objects.requireNonNullElse(query,"").toLowerCase(Locale.ROOT).strip().split("\\s+");
            var matches=PAGES.stream().filter(p->Arrays.stream(words).allMatch(w->(p.id+" "+p.title+" "+p.keywords+" "+p.content).toLowerCase(Locale.ROOT).contains(w))).toList();
            return Map.of("status","OBSERVED","source","bundled:divzero/ui-wiki","total",matches.size(),"nextOffset",offset+16<matches.size()?offset+16:-1,"topics",matches.stream().skip(offset).limit(16).map(p->Map.of("id",p.id,"title",p.title,"officialUrl",p.url,"offline",!p.content.isEmpty())).toList(),"hint","Use topic to read a chapter; online=true retrieves its official URL through the existing public-web reader. Offline chapters are DivZero-specific; official APIs may differ by version.");
        }
        var p=page(topic);String content=p.id.equals("divzero/contract")?InterfaceDefinition.CONTRACT:p.content;int start=Math.min(offset,content.length()),end=Math.min(content.length(),start+length);
        return Map.of("status","OBSERVED","topic",p.id,"title",p.title,"source","bundled:divzero/ui-wiki/"+p.id,"officialUrl",p.url,"content",content.substring(start,end),"nextOffset",end<content.length()?end:-1,"totalCharacters",content.length(),"version","DivZero bridge; LDLib2 26.1.2.41 / KubeJS 8.0.6","authority","REFERENCE_DATA_ONLY");
    }
    private NativeUiWiki(){}
}
