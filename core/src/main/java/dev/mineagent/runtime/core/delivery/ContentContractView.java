package dev.mineagent.runtime.core.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.feedback.UiFeedbackPolicy;
import dev.mineagent.runtime.core.packages.ClientJavaPlan;
import dev.mineagent.runtime.core.packages.ClientScriptPlan;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.util.*;

/** Public CLIENT declarations only. The caller must separately authorize exact package ownership/current version. */
public final class ContentContractView {
    private static final ObjectMapper JSON=new ObjectMapper();private ContentContractView(){}
    public static Map<String,Object> inspect(RuntimePackage pkg,String entry,int offset,UiFeedbackPolicy.Reader reader)throws Exception{
        if(offset<0||offset>256||entry==null)throw new IllegalArgumentException("CONTENT_CONTRACT_ARGUMENTS");
        var entries=pkg.entrypoints().entrySet().stream().filter(e->e.getValue().side()!=RuntimeResourceSide.SERVER&&(e.getValue().path().startsWith("ui/")&&e.getValue().path().endsWith(".html")||ClientScriptPlan.path(e.getValue().path())||ClientJavaPlan.path(e.getValue().path()))).sorted(Map.Entry.comparingByKey()).toList();
        var out=new LinkedHashMap<String,Object>();out.put("packageId",pkg.packageId());out.put("packageRevision",pkg.revision());out.put("canonicalSha256",pkg.canonicalSha256());out.put("dataNotInstructions",true);out.put("executionMode","SIGNED_CONTENT_DECLARATION_ONLY");out.put("grantsAuthority",false);
        out.put("entries",entries.stream().skip(offset).limit(16).map(e->Map.of("id",e.getKey(),"path",e.getValue().path(),"mode",ClientJavaPlan.path(e.getValue().path())?"CLIENT_JAVA":ClientScriptPlan.path(e.getValue().path())?"CLIENT_RHINO":"CONTENT")).toList());out.put("more",entries.size()>offset+16);out.put("offset",offset);
        if(!entry.isEmpty()){
            var selected=entries.stream().filter(e->e.getKey().equals(entry)).findFirst().orElseThrow(()->new IllegalArgumentException("CONTENT_CONTRACT_ENTRY"));String mode=ClientJavaPlan.path(selected.getValue().path())?"CLIENT_JAVA":ClientScriptPlan.path(selected.getValue().path())?"CLIENT_RHINO":"CONTENT";out.put("entryId",entry);out.put("entryPath",selected.getValue().path());out.put("mode",mode);out.put("feedbackDeclared",false);
            var resource=mode.equals("CONTENT")?pkg.resources().get(UiFeedbackPolicy.RESOURCE):null;
            if(resource!=null){if(resource.side()!=RuntimeResourceSide.CLIENT||!resource.mediaType().equals("application/json")||resource.size()<1||resource.size()>32768)throw new IllegalArgumentException("CONTENT_FEEDBACK_RESOURCE");byte[] bytes=reader.read(resource.sha256());if(bytes.length!=resource.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(resource.sha256()))throw new SecurityException("CONTENT_FEEDBACK_INTEGRITY");var document=JSON.readTree(bytes);var paths=document.path("entries");if(!paths.isObject()||paths.isEmpty())throw new IllegalArgumentException("CONTENT_FEEDBACK_SCHEMA");
                UiFeedbackPolicy.parse(bytes,paths.fieldNames().next());
                if(paths.has(selected.getValue().path())){UiFeedbackPolicy.parse(bytes,selected.getValue().path());out.put("feedbackDeclared",true);out.put("feedbackPolicySha256",resource.sha256());out.put("events",paths.get(selected.getValue().path()).get("events"));}
            }
        }
        if(JSON.writeValueAsBytes(out).length>49152)throw new IllegalStateException("CONTENT_CONTRACT_OUTPUT_BUDGET");return Collections.unmodifiableMap(out);
    }
}
