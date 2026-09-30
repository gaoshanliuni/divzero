package dev.mineagent.runtime.core.memory;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative lexical recall: whole words, Chinese phrases and explicit entity aliases, never single-character overlap. */
public final class MemoryRelevance {
    private static final Pattern WORDS=Pattern.compile("[\\p{IsLatin}0-9_:.-]{2,}|[\\p{IsHan}]{2,}");
    private static final Set<String> STOP=Set.of("please","what","that","this","with","have","like","want","give","tell","about","today","可以","什么","怎么","一下","现在","帮我","给我","我的","喜欢","一个","这个","那个");
    private static final List<List<String>> ALIASES=List.of(
        List.of("home","回家","家里","住处","基地","家的位置"),
        List.of("equipment","gear","armor","装备","护甲","胸甲","裤子"),
        List.of("farm","农田","田地","种田"),List.of("chest","箱子","储物箱"));
    private MemoryRelevance(){}
    static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static Set<String> terms(String text){
        var terms=new HashSet<String>();var matcher=WORDS.matcher(normalize(text));
        while(matcher.find()){
            String word=matcher.group();if(!STOP.contains(word))terms.add(word);
            if(word.codePoints().allMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN))
                for(int i=0;i+2<=word.length();i++){String pair=word.substring(i,i+2);if(!STOP.contains(pair))terms.add(pair);}
        }
        return terms;
    }
    public static int score(String query,String topic,String value,List<String> aliases){
        String q=normalize(query),title=normalize(topic),text=title+" "+normalize(value);var tokens=terms(q);var known=terms(text);
        int score=title.equals("家")&&(q.contains("回家")||q.contains("家里")||q.contains("家的")||q.contains("我家")||containsAlias(q,"home"))?8:0;for(String term:tokens)if(known.contains(term))score+=term.length()>=3?6:3;
        if(title.length()>=2&&containsAlias(q,title))score+=8;
        for(String alias:aliases)if(alias.length()>=2&&containsAlias(q,normalize(alias)))score+=8;
        for(var group:ALIASES)if(group.stream().anyMatch(alias->containsAlias(q,alias))&&group.stream().anyMatch(alias->containsAlias(text,alias)))score+=6;
        return score;
    }
    private static boolean containsAlias(String text,String alias){return alias.matches("[a-z0-9_ :.-]+")?Pattern.compile("(?<![a-z0-9])"+Pattern.quote(alias)+"(?![a-z0-9])").matcher(text).find():text.contains(alias);}
    public static final int MINIMUM=6;
}
