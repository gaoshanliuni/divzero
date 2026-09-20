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

        var nodes = new ArrayDeque<Node>();
        nodes.add(root);
        while (!nodes.isEmpty()) {
            Node node = nodes.removeFirst();
            if (node instanceof WhileLoop loop && loop.getCondition().getType() == Token.TRUE) {
                diagnostics.add(infiniteLoop(loop));
            } else if (node instanceof ForLoop loop
                    && (loop.getCondition() == null
                    || loop.getCondition() instanceof EmptyExpression
                    || loop.getCondition().getType() == Token.EMPTY)) {
                diagnostics.add(infiniteLoop(loop));
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                nodes.addLast(child);
            }
        }
        return new PreflightResult(diagnostics.isEmpty(), diagnostics);
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
