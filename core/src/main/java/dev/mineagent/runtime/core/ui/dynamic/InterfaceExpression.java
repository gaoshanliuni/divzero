package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Bounded data expressions for native UI interactions; no eval, Java access, file access or loops. */
public final class InterfaceExpression {
    private static final Set<String> OPS=Set.of("add","sub","mul","div","min","max","eq","ne","lt","lte","gt","gte","and","or","not","if","contains","startsWith","lower","upper","concat","length","at","join","clamp","round");
    public static void validate(JsonNode expr){validate(expr,0,new int[]{0});}
    private static void validate(JsonNode n,int depth,int[] count){
        if(n==null||depth>24||++count[0]>256)throw bad("BUDGET");if(n.isValueNode())return;
        if(!n.isObject())throw bad("OBJECT");
        if(n.has("literal")){if(n.size()!=1||n.get("literal").toString().length()>16384)throw bad("LITERAL");return;}
        if(n.has("data")){if(n.size()!=1||!n.get("data").isTextual()||!n.get("data").asText().matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw bad("KEY");return;}
        if(n.size()!=2||!n.has("op")||!n.has("args")||!n.get("args").isArray()||!OPS.contains(n.path("op").asText()))throw bad("OP");
        int size=n.get("args").size();String op=n.get("op").asText();int arity=Set.of("not","lower","upper","length","round").contains(op)?1:Set.of("if","clamp").contains(op)?3:2;
        if(Set.of("add","mul","min","max","and","or","concat").contains(op)){if(size<1||size>16)throw bad("ARITY");}else if(size!=arity)throw bad("ARITY");
        for(var argument:n.get("args"))validate(argument,depth+1,count);
    }
    public static JsonNode evaluate(JsonNode expr,Map<String,JsonNode> data){validate(expr);return eval(expr,data);}
    private static JsonNode eval(JsonNode n,Map<String,JsonNode> data){
        if(n.isValueNode())return n.deepCopy();if(n.has("literal"))return n.get("literal").deepCopy();if(n.has("data"))return data.getOrDefault(n.get("data").asText(),NullNode.instance).deepCopy();
        String op=n.get("op").asText();var args=n.get("args");
        if(op.equals("if"))return eval(args.get(truth(eval(args.get(0),data))?1:2),data);
        if(op.equals("and")||op.equals("or")){boolean and=op.equals("and");for(var arg:args){boolean value=truth(eval(arg,data));if(value!=and)return BooleanNode.valueOf(!and);}return BooleanNode.valueOf(and);}
        var values=new ArrayList<JsonNode>();for(var arg:args)values.add(eval(arg,data));JsonNode a=values.getFirst(),b=values.size()>1?values.get(1):NullNode.instance;
        return switch(op){
            case "eq"->BooleanNode.valueOf(equal(a,b));case "ne"->BooleanNode.valueOf(!equal(a,b));
            case "not"->BooleanNode.valueOf(!truth(a));
            case "lt"->BooleanNode.valueOf(number(a)<number(b));case "lte"->BooleanNode.valueOf(number(a)<=number(b));case "gt"->BooleanNode.valueOf(number(a)>number(b));case "gte"->BooleanNode.valueOf(number(a)>=number(b));
            case "contains"->BooleanNode.valueOf(string(a).toLowerCase(Locale.ROOT).contains(string(b).toLowerCase(Locale.ROOT)));
            case "startsWith"->BooleanNode.valueOf(string(a).startsWith(string(b)));
            case "lower"->text(string(a).toLowerCase(Locale.ROOT));case "upper"->text(string(a).toUpperCase(Locale.ROOT));
            case "concat"->{var value=new StringBuilder();for(var v:values){value.append(string(v));if(value.length()>16384)throw bad("TEXT_SIZE");}yield text(value.toString());}
            case "length"->IntNode.valueOf(a.isArray()||a.isObject()?a.size():string(a).length());
            case "at"->{if(!a.isArray()||!b.isIntegralNumber()||!b.canConvertToInt()||b.intValue()<0||b.intValue()>=a.size())throw bad("INDEX");yield a.get(b.intValue()).deepCopy();}
            case "join"->{if(!a.isArray()||a.size()>2048)throw bad("ARRAY");var out=new StringBuilder();String separator=string(b);boolean first=true;for(var v:a){String part=string(v);if(out.length()+part.length()+(first?0:separator.length())>16384)throw bad("TEXT_SIZE");if(!first)out.append(separator);out.append(part);first=false;}yield text(out.toString());}
            case "add"->numeric(values.stream().mapToDouble(InterfaceExpression::number).sum());
            case "mul"->{double value=1;for(var v:values)value*=number(v);yield numeric(value);}
            case "min"->numeric(values.stream().mapToDouble(InterfaceExpression::number).min().orElseThrow());case "max"->numeric(values.stream().mapToDouble(InterfaceExpression::number).max().orElseThrow());
            case "sub"->numeric(number(a)-number(b));case "div"->{double divisor=number(b);if(divisor==0)throw bad("DIVISION_BY_ZERO");yield numeric(number(a)/divisor);}
            case "clamp"->{double low=number(b),high=number(values.get(2));if(low>high)throw bad("RANGE");yield numeric(Math.clamp(number(a),low,high));}
            case "round"->numeric(Math.rint(number(a)));
            default->throw bad("OP");
        };
    }
    private static double number(JsonNode n){if(!n.isNumber()||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>9_007_199_254_740_991D)throw bad("NUMBER");return n.doubleValue();}
    private static boolean equal(JsonNode a,JsonNode b){return a.isNumber()&&b.isNumber()?Double.compare(number(a),number(b))==0:a.equals(b);}
    public static boolean truth(JsonNode n){if(!n.isBoolean())throw bad("BOOLEAN");return n.booleanValue();}
    private static String string(JsonNode n){if(!n.isValueNode())throw bad("SCALAR");return n.isNull()?"":n.asText();}
    private static JsonNode numeric(double value){if(!Double.isFinite(value)||Math.abs(value)>9_007_199_254_740_991D)throw bad("NUMBER");return value==Math.rint(value)?LongNode.valueOf((long)value):DoubleNode.valueOf(value);}
    private static JsonNode text(String value){if(value.length()>16384)throw bad("TEXT_SIZE");return TextNode.valueOf(value);}
    private static IllegalArgumentException bad(String code){return new IllegalArgumentException("INTERFACE_EXPRESSION_"+code);}
    private InterfaceExpression(){}
}
