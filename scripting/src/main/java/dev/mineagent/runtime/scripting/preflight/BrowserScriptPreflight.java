package dev.mineagent.runtime.scripting.preflight;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Acorn parses browser ECMAScript as data. Rhino executes only the pinned trusted parser, never the target source. */
public final class BrowserScriptPreflight {
    private static final String HASH = "fdb08546776ec6228b03e8d02b40d4ab3255bae5f401adba7ff5dad927ac5c9c";
    private static final String PARSER = loadParser();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSPECT = """
        (function(source) {
          function possibleExit(body) {
            var todo=[body];
            while(todo.length) {
              var node=todo.pop(); if(!node || typeof node!=='object') continue;
              if(node.type==='BreakStatement' || node.type==='ReturnStatement' || node.type==='ThrowStatement') return true;
              if(node.type==='FunctionDeclaration' || node.type==='FunctionExpression' || node.type==='ArrowFunctionExpression') continue;
              Object.keys(node).forEach(function(k) {
                var v=node[k]; if(k==='loc') return;
                if(Array.isArray(v)) v.forEach(function(x) { if(x && typeof x==='object') todo.push(x); });
                else if(v && typeof v==='object') todo.push(v);
              });
            }
            return false;
          }
          try {
            var tree = acorn.parse(source, {ecmaVersion:2023,sourceType:'module',locations:true});
            var queue=[tree], count=0, diagnostics=[];
            while(queue.length) {
              if(++count > 200000) throw new Error('AST_BUDGET');
              var n=queue.pop();
              if(!n || typeof n !== 'object') continue;
              if(((n.type==='WhileStatement' || n.type==='DoWhileStatement') && n.test.type==='Literal' && n.test.value===true
                  || n.type==='ForStatement' && n.test===null) && !possibleExit(n.body)) {
                diagnostics.push({code:'INFINITE_LOOP',line:n.loc.start.line,message:'检测到没有退出条件的浏览器循环'});
              }
              Object.keys(n).forEach(function(k) {
                if(k==='loc') return;
                var value=n[k];
                if(Array.isArray(value)) value.forEach(function(v) { if(v && typeof v==='object') queue.push(v); });
                else if(value && typeof value==='object') queue.push(value);
              });
            }
            return JSON.stringify({accepted:diagnostics.length===0,diagnostics:diagnostics});
          } catch(error) {
            return JSON.stringify({accepted:false,diagnostics:[{code:'BROWSER_SYNTAX',line:error.loc ? error.loc.line : 1,message:String(error.message)}]});
          }
        })
        """;
    public PreflightResult inspect(String source) {
        if (source == null || source.length() > 1_048_576) return rejected("BROWSER_SOURCE_LIMIT", "浏览器脚本超过预检预算");
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            Context context = new ContextFactory() {
                @Override protected Context createContext() {
                    return new Context(this) {
                        { setGenerateObserverCount(true); setInstructionObserverThreshold(10_000); }
                        @Override protected void observeInstructionCount(int count) {
                            if (System.nanoTime() >= deadline) throw new IllegalStateException("BROWSER_PARSER_TIMEOUT");
                        }
                    };
                }
            }.enter();
            var scope = context.initStandardObjects();
            context.evaluateString(scope, PARSER, "acorn-8.15.0.js", 1, null);
            Object result = context.evaluateString(scope, INSPECT + "(" + JSON.writeValueAsString(source) + ");", "browser-preflight.js", 1, null);
            var value = JSON.readTree(result.toString());
            var diagnostics = new ArrayList<PreflightDiagnostic>();
            for (var d : value.path("diagnostics")) diagnostics.add(new PreflightDiagnostic(d.path("code").asText(),
                    Math.max(1, d.path("line").asInt(1)), d.path("message").asText()));
            return new PreflightResult(value.path("accepted").asBoolean(false), diagnostics);
        } catch (Exception failure) { return rejected("BROWSER_PARSER_FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
    }
    private static PreflightResult rejected(String code, String message) {
        return new PreflightResult(false, List.of(new PreflightDiagnostic(code, 1, message)));
    }
    private static String loadParser() {
        try (var in = BrowserScriptPreflight.class.getResourceAsStream("acorn-8.15.0.js")) {
            if (in == null) throw new IllegalStateException("MISSING_ACORN");
            byte[] bytes = in.readAllBytes();
            if (!HASH.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalStateException("ACORN_HASH_MISMATCH");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new IllegalStateException("Cannot load verified Acorn parser", failure); }
    }
}
