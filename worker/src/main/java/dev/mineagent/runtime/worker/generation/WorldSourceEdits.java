package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.scripting.opencode.OpenCodeRuntime;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Sparse, exact edits for an installed package or its retained failed candidate. No activation here. */
public final class WorldSourceEdits {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static String apply(RuntimePackage base,ContentAddressedStore content,String previous,List<FailedCandidatePatch.Change> edits)throws Exception{
        if(edits.isEmpty()||edits.size()>32)throw new IllegalArgumentException("WORLD_PATCH_EDIT_COUNT");
        var files=new LinkedHashMap<String,ObjectNode>();var root=JSON.createObjectNode();
        if(previous!=null&&!previous.isBlank()){
            if(previous.length()>4*1024*1024)throw new IllegalArgumentException("WORLD_PATCH_OUTPUT_LIMIT");
            var old=JSON.readTree(previous);if(!old.isObject()||!old.path("files").isArray())throw new PackageOutputException("WORLD_PATCH_EDIT_SOURCE","The prior sparse candidate is not valid JSON; inspect its raw source.");
            for(var file:old.path("files")){String path=file.path("path").asText();if(!(file instanceof ObjectNode object)||files.putIfAbsent(path,object.deepCopy())!=null)throw new IllegalArgumentException("WORLD_PATCH_DUPLICATE_PATH");}
            root=(ObjectNode)old.deepCopy();
        }
        for(int index=0;index<edits.size();index++){
            var edit=edits.get(index);var existing=base.resources().get(edit.path());var file=files.get(edit.path());String text;
            if(existing==null||!(edit.path().endsWith(".js")||edit.path().endsWith(".java")||edit.path().endsWith(".json")||existing.mediaType().startsWith("text/")))throw new PackageOutputException("WORLD_PATCH_EDIT_SOURCE","edits["+index+"].path: expected an existing text source: "+edit.path());
            if(file!=null){if(!file.path("encoding").asText("utf8").equals("utf8")||!file.path("content").isTextual())throw new IllegalArgumentException("WORLD_PATCH_EDIT_ENCODING");text=file.get("content").asText();}
            else{if(existing.size()>1048576)throw new IllegalArgumentException("WORLD_PATCH_FILE_BUDGET");text=new String(content.read(existing.sha256()),StandardCharsets.UTF_8);}
            var patched=OpenCodeRuntime.edit(text,edit.oldText(),edit.newText(),edit.replaceAll());
            if(!patched.accepted())throw new PackageOutputException("WORLD_PATCH_EDIT_REJECTED",edit.path()+" edits["+index+"].old_text: "+patched.error());
            files.put(edit.path(),JSON.createObjectNode().put("path",edit.path()).put("content",patched.source()).put("encoding","utf8"));
        }
        var entries=root.putArray("files");files.values().forEach(entries::add);return JSON.writeValueAsString(root);
    }
    private WorldSourceEdits(){}
}
