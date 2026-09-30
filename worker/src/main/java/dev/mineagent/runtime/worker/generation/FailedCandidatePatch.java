package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.scripting.opencode.OpenCodeRuntime;
import java.util.*;

/** Pure source edits against a failed candidate. Other files and running package versions are untouched. */
public final class FailedCandidatePatch {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Change(String path,String oldText,String newText,boolean replaceAll){}
    public static String apply(String raw,List<Change> changes)throws Exception{
        if(changes.isEmpty()||changes.size()>32)throw new IllegalArgumentException("CANDIDATE_EDIT_COUNT");
        String output=raw;
        for(int index=0;index<changes.size();index++){
            var change=changes.get(index);if(change.path==null||change.oldText==null||change.newText==null)throw new IllegalArgumentException("CANDIDATE_EDIT_FIELD");
            if(change.path.equals("raw_output")){var edited=OpenCodeRuntime.edit(output,change.oldText,change.newText,change.replaceAll);if(!edited.accepted())throw new PackageOutputException("CANDIDATE_EDIT_REJECTED","edits["+index+"].old_text: "+edited.error());output=edited.source();continue;}
            var root=JSON.readTree(output);if(!(root instanceof ObjectNode)||!root.path("files").isArray())throw new PackageOutputException("CANDIDATE_EDIT_REJECTED","raw_output is not valid package JSON; repair its syntax first.");
            ObjectNode selected=null;for(var file:root.path("files"))if(change.path.equals(file.path("path").asText())){if(selected!=null)throw new PackageOutputException("CANDIDATE_EDIT_REJECTED","Duplicate file path "+change.path);selected=(ObjectNode)file;}
            if(selected==null||!selected.path("encoding").asText().equals("utf8"))throw new PackageOutputException("CANDIDATE_EDIT_REJECTED","edits["+index+"].path: expected an existing UTF-8 source file: "+change.path);
            var edited=OpenCodeRuntime.edit(selected.path("content").asText(),change.oldText,change.newText,change.replaceAll);
            if(!edited.accepted())throw new PackageOutputException("CANDIDATE_EDIT_REJECTED",change.path+" edits["+index+"].old_text: "+edited.error());
            selected.put("content",edited.source());output=GeneratedMetadata.complete(JSON.writeValueAsString(root));
        }return GeneratedMetadata.complete(output);
    }
    private FailedCandidatePatch(){}
}
