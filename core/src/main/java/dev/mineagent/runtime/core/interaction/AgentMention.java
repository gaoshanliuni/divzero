package dev.mineagent.runtime.core.interaction;

import java.util.*;

/** Shared token grammar for native chat resolution and Brigadier completion. Slash commands remain vanilla. */
public final class AgentMention {
    private AgentMention(){}
    public record Match(String name,String message,int start,int end){}
    public record Completion(int start,List<String> values){}
    public static String token(String name){return name.codePoints().anyMatch(c->Character.isWhitespace(c)||c=='"'||c=='\\')?"@\""+name.replace("\\","\\\\").replace("\"","\\\"")+"\"":"@"+name;}
    private static boolean boundary(char c){return Character.isWhitespace(c)||",，:：!?！？。".indexOf(c)>=0;}
    public static List<Match> resolve(String input,Collection<String> names){
        if(input==null||input.stripLeading().startsWith("/"))return List.of();var found=new ArrayList<Match>();
        for(int i=0;i<input.length();i++){if(input.charAt(i)!='@'||i>0&&!boundary(input.charAt(i-1)))continue;int start=i;
            var candidates=new ArrayList<Match>();for(String name:names){for(String t:List.of(token(name),"@ai "+token(name).substring(1),"@"+name)){int end=start+t.length();if(end<=input.length()&&input.regionMatches(true,start,t,0,t.length())&&(end==input.length()||boundary(input.charAt(end))))candidates.add(new Match(name,(input.substring(0,start)+input.substring(end)).strip(),start,end));}}
            int max=candidates.stream().mapToInt(Match::end).max().orElse(-1);candidates.stream().filter(m->m.end()==max).distinct().forEach(found::add);if(max>i)i=max-1;
        }return List.copyOf(found);
    }
    public static Optional<Completion> complete(String input,int cursor,Collection<String> names){
        if(input==null||cursor<0||cursor>input.length()||input.stripLeading().startsWith("/"))return Optional.empty();
        String partial=input.substring(0,cursor);
        // Check the outer mention first: an @ inside a quoted AI name is not a new token.
        for(int at=0;at<partial.length();at++){
            if(partial.charAt(at)!='@'||at>0&&!boundary(partial.charAt(at-1)))continue;
            String fragment=partial.substring(at);
            boolean alias=fragment.toLowerCase(Locale.ROOT).startsWith("@ai ");
            var values=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for(String name:names){
                String option=alias?"@ai "+token(name).substring(1):token(name);
                String unquoted=(alias?"@ai ":"@")+name;
                // Users type the name, not its escaping syntax; insertion handles quoting automatically.
                if(option.regionMatches(true,0,fragment,0,fragment.length())||unquoted.regionMatches(true,0,fragment,0,fragment.length()))values.add(option+" ");
            }
            // An AI may itself be named "AI Helper". Prefer the explicit alias catalog,
            // but fall back to the direct name when no alias candidate matches its suffix.
            if(values.isEmpty()&&alias)for(String name:names){
                String option=token(name),unquoted="@"+name;
                if(option.regionMatches(true,0,fragment,0,fragment.length())||unquoted.regionMatches(true,0,fragment,0,fragment.length()))values.add(option+" ");
            }
            if(!values.isEmpty())return Optional.of(new Completion(at,List.copyOf(values)));
        }
        return Optional.empty();
    }
}
