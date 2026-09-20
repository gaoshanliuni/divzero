package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.objects.RuntimeModelBundle;
import dev.mineagent.runtime.scripting.preflight.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Signed sparse world source/asset repair. It never executes the candidate or changes UI/permissions/definitions. */
public final class WorldPackagePatchBuilder {
    private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private WorldPackagePatchBuilder(){}
    public static RuntimePackage prepare(RuntimePackage base,String output,ContentAddressedStore store,IdentitySigner signer)throws Exception{
        WorldPatchPolicy.requireBase(base);
        if(output==null||output.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IllegalArgumentException("WORLD_PATCH_OUTPUT_LIMIT");
        boolean boot=base.activationMode()==ActivationMode.BOOT_EXTENSION;var root=JSON.readTree(output);only(root,boot?Set.of("files","delete","version","dependencies"):Set.of("files","delete"));
        if(!root.path("files").isArray()||root.path("files").size()>64)throw new IllegalArgumentException("WORLD_PATCH_FILES");
        var resources=new LinkedHashMap<>(base.resources());var changed=new HashSet<String>();
        for(var file:root.path("files")){
            only(file,Set.of("path","content","encoding"));String path=text(file,"path");path(path);if(!changed.add(path))throw new IllegalArgumentException("WORLD_PATCH_DUPLICATE_PATH");
            String value=text(file,"content"),encoding=file.has("encoding")?text(file,"encoding"):"utf8";
            byte[] bytes=switch(encoding){case "utf8"->value.getBytes(StandardCharsets.UTF_8);case "base64"->Base64.getDecoder().decode(value);default->throw new IllegalArgumentException("WORLD_PATCH_ENCODING");};
            if(bytes.length>1_048_576)throw new IllegalArgumentException("WORLD_PATCH_FILE_BUDGET");
            var old=base.resources().get(path);String media=old!=null?old.mediaType():path.endsWith(".js")?"application/javascript":path.endsWith(".json")?"application/json":path.endsWith(".png")?"image/png":boot?(path.endsWith(".java")?"text/x-java-source":"application/octet-stream"):null;
            if(media==null)throw new IllegalArgumentException("WORLD_PATCH_MEDIA");
            var side=old!=null?old.side():boot?base.entrypoints().get("boot").side():path.startsWith("server/")?RuntimeResourceSide.SERVER:RuntimeResourceSide.COMMON;
            if(media.equals("application/json"))JSON.readTree(bytes);
            var blob=store.put(bytes);resources.put(path,new RuntimeResourceRef(path,blob.sha256(),side,media,bytes.length));
        }
        if(root.has("delete")){
            if(!root.get("delete").isArray()||root.get("delete").size()>64)throw new IllegalArgumentException("WORLD_PATCH_DELETE");
            for(var value:root.get("delete")){if(!value.isTextual())throw new IllegalArgumentException("WORLD_PATCH_DELETE");String path=value.textValue();path(path);if(!changed.add(path)||resources.remove(path)==null)throw new IllegalArgumentException("WORLD_PATCH_DELETE");}
        }
        if(resources.size()>256||resources.values().stream().mapToLong(RuntimeResourceRef::size).sum()>16L*1024*1024)throw new IllegalArgumentException("WORLD_PATCH_TOTAL_BUDGET");
        var entries=new LinkedHashMap<String,RuntimeEntrypoint>();
        for(var item:base.entrypoints().entrySet()){
            var e=item.getValue();var ref=resources.get(e.path());if(ref==null)throw new IllegalArgumentException("WORLD_PATCH_ENTRYPOINT_DELETE");entries.put(item.getKey(),new RuntimeEntrypoint(e.path(),e.side(),ref.sha256()));
        }
        Map<UUID,String> dependencies=base.dependencies();
        if(boot&&root.has("dependencies")){var node=root.get("dependencies");if(!node.isObject()||node.size()>64)throw new IllegalArgumentException("WORLD_PATCH_DEPENDENCIES");var values=new LinkedHashMap<UUID,String>();for(var field:node.properties()){UUID id=UUID.fromString(field.getKey());if(id.equals(base.packageId())||!field.getValue().isTextual()||!field.getValue().asText().matches("[0-9A-Za-z_.+-]{1,64}"))throw new IllegalArgumentException("WORLD_PATCH_DEPENDENCIES");values.put(id,field.getValue().asText());}dependencies=Map.copyOf(values);}
        var draft=new RuntimePackage(base.packageId(),base.type(),base.name(),boot&&root.has("version")?text(root,"version"):base.version(),base.activationMode(),dependencies,base.permissions(),entries,base.definitions(),resources,base.origin(),base.enabled(),base.revision()+1,"0".repeat(64),"",System.currentTimeMillis(),base.nativeCompatibility());
        if(draft.entrypoints().containsKey("client"))ClientScriptPlan.inspect(draft);
        if(draft.entrypoints().containsKey("client_java"))ClientJavaPlan.inspect(draft);
        FeedbackPackageContract.validate(draft,store::read);
        if(boot){var before=dev.mineagent.runtime.core.boot.BootExtensionPlan.read(base,store);var after=dev.mineagent.runtime.core.boot.BootExtensionPlan.read(draft,store);if(!before.modId().equals(after.modId()))throw new IllegalArgumentException("WORLD_PATCH_BOOT_MOD_ID");}
        else{
        var plan=WorldContentPlan.resolve(draft,store);var preflight=new ScriptPreflight();var registration=new RegistrationPreflight();
        for(var source:plan.modules().values())if(!preflight.inspect(source).accepted())throw new IllegalArgumentException("WORLD_PATCH_PREFLIGHT");
        for(var restore:plan.restoreEntrypoints().values())for(var module:plan.modules().entrySet()){
            var check=module.getKey().equals(restore)?registration.lifecycle(module.getValue()):registration.inspect(module.getValue());if(!check.accepted())throw new IllegalArgumentException("WORLD_PATCH_REGISTRATION");
        }
        for(var d:draft.definitions().values())for(var path:d.resourcePaths()){
            var ref=resources.get(path);if(ref==null)throw new IllegalArgumentException("WORLD_PATCH_DECLARED_RESOURCE");
            if(ref.mediaType().equals("application/json")){
                var data=JSON.readTree(store.read(ref.sha256()));
                if(data.has("collision")&&(data.has("vertices")||data.has("boxes")))try{RuntimeModelBundle.load(draft,d,path,store);}catch(Exception invalid){throw new IllegalArgumentException("WORLD_PATCH_MESH",invalid);}
            }
        }
        }
        String hash=RuntimePackageCanonicalizer.sha256(draft);
        var candidate=new RuntimePackage(draft.packageId(),draft.type(),draft.name(),draft.version(),draft.activationMode(),draft.dependencies(),draft.permissions(),entries,draft.definitions(),resources,draft.origin(),draft.enabled(),draft.revision(),hash,Base64.getEncoder().encodeToString(signer.sign(hash.getBytes(StandardCharsets.US_ASCII))),draft.updatedAtEpochMillis(),draft.nativeCompatibility());
        WorldPatchPolicy.require(base,candidate);return candidate;
    }
    private static void path(String path){RuntimeEntrypoint.requireRelativePath(path);if(path.length()>256||path.startsWith("ui/")||!path.matches("[A-Za-z0-9_@.-]+(?:/[A-Za-z0-9_@.-]+)*"))throw new IllegalArgumentException("WORLD_PATCH_PATH");}
    private static String text(JsonNode node,String key){if(!node.path(key).isTextual())throw new IllegalArgumentException("WORLD_PATCH_FIELD");return node.path(key).textValue();}
    private static void only(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.properties().stream().anyMatch(e->!fields.contains(e.getKey())))throw new IllegalArgumentException("WORLD_PATCH_FIELDS");}
}
