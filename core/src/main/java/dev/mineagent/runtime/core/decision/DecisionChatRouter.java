package dev.mineagent.runtime.core.decision;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.core.task.TaskManager;
import java.util.*;
import java.util.regex.*;
/** Deterministic, context-bound ordinary-design answer routing. Never interprets chat as a permission grant. */
public final class DecisionChatRouter {
    public record Routed(boolean handled,String code,DecisionSubmitResult result){}
    private record Parsed(List<Integer> indices,String extra){}
    private static final String NUMBER="[0-9一二三四五六七八九十两]+";
    private static final Pattern FIRST=Pattern.compile("^(?:我选|选择|选)?\\s*第?("+NUMBER+")(?:个|项|号)(?:选项|方案)?");
    private static final Pattern MORE=Pattern.compile("^\\s*[、，,和及]\\s*第?("+NUMBER+")(?:个|项|号)(?:选项|方案)?");
    private final DecisionService decisions;private final TaskManager tasks;
    private final java.util.function.BiPredicate<DecisionRequest,UUID> externalContext;
    private final java.util.function.BiPredicate<DecisionRequest,UUID> canAnswer;
    public DecisionChatRouter(DecisionService decisions,TaskManager tasks){this(decisions,tasks,(q,a)->false);}
    public DecisionChatRouter(DecisionService decisions,TaskManager tasks,java.util.function.BiPredicate<DecisionRequest,UUID> externalContext){this(decisions,tasks,externalContext,(q,v)->true);}
    public DecisionChatRouter(DecisionService decisions,TaskManager tasks,java.util.function.BiPredicate<DecisionRequest,UUID> externalContext,java.util.function.BiPredicate<DecisionRequest,UUID> canAnswer){this.decisions=decisions;this.tasks=tasks;this.externalContext=externalContext;this.canAnswer=canAnswer;}
    public static boolean ordinalAnswer(String text){return text!=null&&parse(text.strip())!=null;}
    public Routed route(UUID viewer,UUID agent,UUID selected,Long revision,UUID operation,String text){
        if(text==null||text.isBlank()||text.length()>8192)return new Routed(selected!=null,"DECISION_TEXT_INVALID",null);
        Parsed parsed=parse(text.strip());if(selected==null&&parsed==null)return new Routed(false,"",null);
        decisions.reconcileTasks(tasks);
        DecisionRequest q;
        if(selected==null){
            var matching=decisions.pendingFor(viewer).stream().filter(d->context(d,viewer,agent)).toList();
            if(matching.size()!=1)return new Routed(true,matching.isEmpty()?"NO_OPEN_DECISION":"DECISION_CONTEXT_REQUIRED",null);q=matching.getFirst();
        }else{
            q=decisions.get(selected).orElse(null);
            if(q==null||!q.recipientPlayerId().equals(viewer)||!context(q,viewer,agent))return new Routed(true,"DECISION_CONTEXT_MISMATCH",null);
        }
        if(!canAnswer.test(q,viewer))return new Routed(true,"DECISION_AUTHORITY_REVOKED",DecisionSubmitResult.rejected(q,"DECISION_AUTHORITY_REVOKED"));
        if(q.kind()==DecisionKind.AUTHORIZATION)return new Routed(true,"AUTHORIZATION_REQUIRES_UI",null);
        var indices=parsed==null?List.<Integer>of():parsed.indices();var options=new ArrayList<String>();
        for(int n:indices){if(n<1||n>q.options().size())return new Routed(true,"DECISION_OPTION_OUT_OF_RANGE",DecisionSubmitResult.rejected(q,"UNKNOWN_OPTION"));options.add(q.options().get(n-1).optionId());}
        String extra=parsed==null?text.strip():parsed.extra();
        var answer=new DecisionAnswerSubmission(q.decisionId(),revision==null?q.revision():revision,operation,options,extra,AnswerSource.CHAT);
        var result=decisions.submitForTask(viewer,tasks,answer);return new Routed(true,result.errorCode(),result);
    }
    private boolean context(DecisionRequest request,UUID viewer,UUID agent){
        var link=decisions.taskLink(request.decisionId()).orElse(null);if(link==null)return externalContext.test(request,agent);
        var task=tasks.get(link.taskId()).orElse(null);return task!=null&&task.ownerPlayerId().equals(viewer)&&task.agentId().equals(agent);
    }
    private static Parsed parse(String text){
        var first=FIRST.matcher(text);if(!first.find())return null;var indices=new ArrayList<Integer>();indices.add(number(first.group(1)));String rest=text.substring(first.end());
        for(int i=0;i<64;i++){var more=MORE.matcher(rest);if(!more.find())break;indices.add(number(more.group(1)));rest=rest.substring(more.end());}
        rest=rest.strip();if(!rest.isEmpty()&&!rest.matches("^(?:但|不过|同时|并且|加上|[，,。；;]).*"))return null;
        rest=rest.replaceFirst("^[，,。；;]\\s*","");return new Parsed(List.copyOf(indices),rest);
    }
    private static int number(String value){
        try{return Integer.parseInt(value);}catch(NumberFormatException ignored){}
        value=value.replace('两','二');String digits="零一二三四五六七八九";
        if(value.equals("十"))return 10;if(value.length()==1)return digits.indexOf(value.charAt(0));
        int ten=value.indexOf('十');if(ten<0||ten>1||value.length()>3)return -1;
        int prefix=ten==0?1:digits.indexOf(value.charAt(0));int suffix=ten==value.length()-1?0:digits.indexOf(value.charAt(ten+1));return prefix>0&&suffix>=0?prefix*10+suffix:-1;
    }
}
