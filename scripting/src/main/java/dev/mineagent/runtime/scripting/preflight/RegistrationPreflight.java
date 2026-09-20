package dev.mineagent.runtime.scripting.preflight;

import dev.latvian.mods.rhino.*;
import dev.latvian.mods.rhino.ast.*;
import java.util.*;

/** Opt-in reload contract: module initialization declares literals/functions/handlers only.
 * Callback bodies retain native Java interop; this is not a sandbox for their later effects. */
public final class RegistrationPreflight {
    private static final Set<String> RESERVED=Set.of("on","require","schedule","track","content","instance","server","level","eval","globalThis","undefined","NaN","Infinity");
    public PreflightResult inspect(String source){return inspect(source,false);}
    public PreflightResult lifecycle(String source){return inspect(source,true);}
    private PreflightResult inspect(String source,boolean lifecycle){
        var errors=new ArrayList<PreflightDiagnostic>();var events=new HashSet<String>();
        if(source==null||source.isBlank()||source.length()>1_000_000)return new PreflightResult(false,List.of(new PreflightDiagnostic("REGISTRATION_SOURCE_LIMIT",1,"注册模块为空或超过预算")));
        try{
            var root=new Parser(new Context(new ContextFactory())).parse(source,"registration.js",1);
            for(Node child=root.getFirstChild();child!=null;child=child.getNext()){
                var node=(AstNode)child;
                if(!declaration(node,events))errors.add(new PreflightDiagnostic("REGISTRATION_SIDE_EFFECT",Math.max(1,node.getLineno()),"模块顶层只允许常量、函数声明与 on(event, function) 注册；原生操作应放入生命周期回调"));
            }
            if(lifecycle&&!events.containsAll(Set.of("instance.create","instance.restore")))errors.add(new PreflightDiagnostic("LIFECYCLE_HANDLERS_REQUIRED",1,"需要明确的 instance.create 与 instance.restore 回调"));
        }catch(RuntimeException e){errors.add(new PreflightDiagnostic("REGISTRATION_SYNTAX",1,"无法解析注册模块"));}
        return new PreflightResult(errors.isEmpty(),errors);
    }
    private boolean declaration(AstNode node,Set<String> events){
        if(node instanceof EmptyStatement)return true;
        if(node instanceof FunctionNode f)return f.getFunctionType()==FunctionNode.FUNCTION_STATEMENT&&!f.getName().isBlank()&&!RESERVED.contains(f.getName());
        if(node instanceof VariableDeclaration d){
            for(var v:d.getVariables())if(!(v.getTarget() instanceof Name name)||RESERVED.contains(name.getIdentifier())||!literal(v.getInitializer(),0,new int[]{0}))return false;
            return true;
        }
        if(!(node instanceof ExpressionStatement expression))return false;
        var value=expression.getExpression();
        if(value instanceof StringLiteral s)return s.getValue().equals("use strict");
        if(!(value instanceof FunctionCall call)||!(call.getTarget() instanceof Name name)||!name.getIdentifier().equals("on")||call.getArguments().size()!=2)return false;
        if(!(call.getArguments().get(0) instanceof StringLiteral event)||!event.getValue().matches("[A-Za-z0-9_.:-]{1,128}")||!(call.getArguments().get(1) instanceof FunctionNode))return false;
        events.add(event.getValue());return true;
    }
    private boolean literal(AstNode node,int depth,int[] count){
        if(node==null)return true;
        if(depth>32||++count[0]>4096)return false;
        if(node instanceof StringLiteral||node instanceof NumberLiteral)return true;
        if(node instanceof KeywordLiteral)return Set.of(Token.TRUE,Token.FALSE,Token.NULL).contains(node.getType());
        if(node instanceof UnaryExpression u)return (u.getOperator()==Token.NEG||u.getOperator()==Token.POS)&&u.getOperand() instanceof NumberLiteral;
        if(node instanceof ArrayLiteral a){for(var value:a.getElements())if(!literal(value,depth+1,count))return false;return true;}
        if(node instanceof ObjectLiteral o){
            for(var p:o.getElements()){
                if(p.isMethod()||!(p.getLeft() instanceof Name||p.getLeft() instanceof StringLiteral||p.getLeft() instanceof NumberLiteral)||!literal(p.getRight(),depth+1,count))return false;
                String key=p.getLeft() instanceof Name n?n.getIdentifier():p.getLeft() instanceof StringLiteral s?s.getValue():"";
                if(Set.of("__proto__","prototype","constructor").contains(key))return false;
            }return true;
        }
        return false;
    }
}
