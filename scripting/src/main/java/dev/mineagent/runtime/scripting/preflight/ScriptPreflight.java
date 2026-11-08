package dev.mineagent.runtime.scripting.preflight;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.Parser;
import dev.latvian.mods.rhino.Token;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.EmptyExpression;
import dev.latvian.mods.rhino.ast.ForLoop;
import dev.latvian.mods.rhino.ast.WhileLoop;
import dev.latvian.mods.rhino.ast.*;

import java.util.ArrayDeque;
import java.util.ArrayList;

public final class ScriptPreflight {
    public PreflightResult inspect(String source) {
        var diagnostics = new ArrayList<PreflightDiagnostic>();
        final AstNode root;
        try {
            var context = new Context(new ContextFactory());
            root = new Parser(context).parse(source, "generated.js", 1);
        } catch (RuntimeException syntaxFailure) {
            diagnostics.add(new PreflightDiagnostic(
                    "SYNTAX_ERROR", syntaxFailure instanceof dev.latvian.mods.rhino.RhinoException location
                            ? Math.max(1, location.lineNumber()) : 1, normalizedMessage(syntaxFailure)
            ));
            return new PreflightResult(false, diagnostics);
        }

        walk(root,node -> {
            AstNode body = null;
            if (node instanceof WhileLoop loop && literalTrue(loop.getCondition())) body = loop.getBody();
            else if (node instanceof ForLoop loop && (loop.getCondition() == null || loop.getCondition() instanceof EmptyExpression
                    || loop.getCondition().getType() == Token.EMPTY || literalTrue(loop.getCondition()))) body = loop.getBody();
            else if (node instanceof DoLoop loop && literalTrue(loop.getCondition())) body = loop.getBody();
            if (body != null && !hasExit(node, body)) diagnostics.add(infiniteLoop(node));
            return true;
        });
        return new PreflightResult(diagnostics.isEmpty(), diagnostics);
    }

    // The pinned KubeJS Rhino fork retains typed AST getters but removes AstNode.visit.
    // Only public, zero-argument AST data accessors are inspected; parent/target links
    // are excluded by the child's actual parent identity. No script Java is invoked.
    private static final ClassValue<java.util.List<java.lang.reflect.Method>> CHILD_GETTERS=new ClassValue<>() {
        protected java.util.List<java.lang.reflect.Method> computeValue(Class<?> type){return java.util.Arrays.stream(type.getMethods()).filter(m->m.getParameterCount()==0&&m.getName().startsWith("get")&&(AstNode.class.isAssignableFrom(m.getReturnType())||Iterable.class.isAssignableFrom(m.getReturnType()))).toList();}
    };
    private static void walk(AstNode root,NodeVisitor visitor){
        var pending=new ArrayDeque<AstNode>();var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<AstNode,Boolean>());pending.add(root);
        while(!pending.isEmpty()){
            var node=pending.removeFirst();if(!seen.add(node)||!visitor.visit(node))continue;
            for(Node child=node.getFirstChild();child!=null;child=child.getNext())if(child instanceof AstNode ast)pending.add(ast);
            for(var getter:CHILD_GETTERS.get(node.getClass()))try{
                Object value=getter.invoke(node);
                if(value instanceof AstNode ast&&ast.getParent()==node)pending.add(ast);
                else if(value instanceof Iterable<?> children)for(var child:children)if(child instanceof AstNode ast&&ast.getParent()==node)pending.add(ast);
            }catch(ReflectiveOperationException error){throw new IllegalStateException("SCRIPT_AST_TRAVERSAL",error);}
        }
    }

    private static AstNode unparenthesized(AstNode node) {
        while (node instanceof ParenthesizedExpression expression) node = expression.getExpression();
        return node;
    }
    private static boolean literalTrue(AstNode node) { return node != null && unparenthesized(node).getType() == Token.TRUE; }
    private static boolean encloses(AstNode outer, AstNode inner) {
        if (outer == null) return false;
        for (AstNode node=inner; node!=null; node=node.getParent()) if (node == outer) return true;
        return false;
    }
    /** Possible exits belong to this loop/enclosing function, never to a nested function or switch. */
    private static boolean hasExit(AstNode loop, AstNode body) {
        boolean[] found={false};
        NodeVisitor exits = new NodeVisitor() {
            @Override public boolean visit(AstNode node) {
                if (found[0] || node instanceof FunctionNode) return false;
                if (node instanceof IfStatement branch) {
                    int condition=unparenthesized(branch.getCondition()).getType();
                    if (condition==Token.TRUE || condition==Token.FALSE) {
                        var reachable=condition==Token.TRUE?branch.getThenPart():branch.getElsePart();
                        if (reachable!=null) walk(reachable,this);
                        return false;
                    }
                }
                if (node instanceof BreakStatement stop) {var target=stop.getBreakTarget();if(encloses(target instanceof Label?target.getParent():target,loop))found[0]=true;}
                if (node instanceof ReturnStatement) found[0]=true;
                if (node instanceof ThrowStatement) {
                    boolean caught=false;
                    for(var parent=node.getParent();parent!=null&&parent!=loop;parent=parent.getParent())
                        if(parent instanceof TryStatement attempt&&!attempt.getCatchClauses().isEmpty()&&encloses(attempt.getTryBlock(),node)){caught=true;break;}
                    if(!caught)found[0]=true;
                }
                return !found[0];
            }
        };
        walk(body,exits);return found[0];
    }

    private static PreflightDiagnostic infiniteLoop(AstNode loop) {
        return new PreflightDiagnostic(
                "INFINITE_LOOP",
                Math.max(1, loop.getLineno()),
                "检测到没有退出条件的循环"
        );
    }

    private static String normalizedMessage(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
