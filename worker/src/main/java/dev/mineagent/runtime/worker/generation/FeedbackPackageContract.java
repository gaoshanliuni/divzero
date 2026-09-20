package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.feedback.UiFeedbackPolicy;
import java.util.*;

/** Structural package validation. This never grants feedback or claims the handler executed. */
public final class FeedbackPackageContract {
    private FeedbackPackageContract(){}
    public static void validate(ParsedRuntimePackage p){
        var f=p.files().stream().filter(v->v.path().equals(UiFeedbackPolicy.RESOURCE)).findFirst().orElse(null);if(f==null)return;
        validate(f.side(),f.mediaType(),f.content(),p.permissions(),p.entrypoints(),p.definitions());
    }
    public static void validate(RuntimePackage p,UiPatchPrompt.Reader reader)throws Exception{
        var f=p.resources().get(UiFeedbackPolicy.RESOURCE);if(f==null)return;byte[] bytes=reader.read(f.sha256());
        if(bytes.length!=f.size()||!dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes).equals(f.sha256()))throw new IllegalArgumentException("FEEDBACK_POLICY_RESOURCE_INTEGRITY");
        validate(f.side(),f.mediaType(),bytes,p.permissions(),p.entrypoints(),p.definitions().values());
    }
    private static void validate(RuntimeResourceSide side,String media,byte[] bytes,Set<String> permissions,Map<String,RuntimeEntrypoint> entries,Collection<RuntimeDefinition> definitions){
        try{
            if(side!=RuntimeResourceSide.CLIENT||!media.equals("application/json")||bytes==null||bytes.length<1||bytes.length>32768)throw new IllegalArgumentException();
            var document=new ObjectMapper().readTree(bytes);var declared=document.path("entries");if(!declared.isObject()||declared.isEmpty())throw new IllegalArgumentException();
            var html=entries.values().stream().filter(e->e.side()==RuntimeResourceSide.CLIENT&&e.path().startsWith("ui/")&&e.path().endsWith(".html")).map(RuntimeEntrypoint::path).collect(java.util.stream.Collectors.toSet());
            boolean deterministic=false;
            for(var page:declared.properties()){
                if(!html.contains(page.getKey()))throw new IllegalArgumentException("FEEDBACK_UNBOUND_ENTRY");
                var policy=UiFeedbackPolicy.parse(bytes,page.getKey());deterministic|=policy.events().values().stream().anyMatch(e->e.mode().equals("DETERMINISTIC"));
            }
            var server=entries.get("server");
            if(deterministic&&(!permissions.containsAll(Set.of("RUN_CODE","state.shared"))||server==null||server.side()!=RuntimeResourceSide.SERVER||definitions.stream().noneMatch(d->{var e=entries.get(d.entrypointId());return e!=null&&e.side()!=RuntimeResourceSide.CLIENT;})))throw new IllegalArgumentException("FEEDBACK_WORLD_CONTRACT_REQUIRED");
        }catch(Exception invalid){throw new PackageOutputException("FEEDBACK_PACKAGE_CONTRACT","反馈声明必须匹配真实HTML入口、事件schema和所需的同包世界规则；声明不授予执行权限");}
    }
}
