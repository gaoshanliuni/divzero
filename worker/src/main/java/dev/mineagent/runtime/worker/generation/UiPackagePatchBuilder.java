package dev.mineagent.runtime.worker.generation;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.scripting.preflight.BrowserScriptPreflight;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UI file edits, not a control DSL. Preserve every omitted resource and all gameplay/identity metadata. */
public final class UiPackagePatchBuilder {
    private static final ObjectMapper JSON=new ObjectMapper();
    private UiPackagePatchBuilder(){}
    public static RuntimePackage prepare(RuntimePackage base,String output,ContentAddressedStore store,IdentitySigner signer)throws Exception{
        if(output==null||output.length()>4*1024*1024)throw new IllegalArgumentException("UI_PATCH_OUTPUT_LIMIT");
        JsonNode root=JSON.readTree(output);only(root,Set.of("files","delete"));
        if(!root.path("files").isArray()||root.path("files").size()>32)throw new IllegalArgumentException("UI_PATCH_FILES");
        var resources=new LinkedHashMap<>(base.resources());var changed=new HashSet<String>();var parser=new BrowserScriptPreflight();
        for(var file:root.path("files")){
            only(file,Set.of("path","content","encoding"));String path=text(file,"path");path(path);
            if(!changed.add(path))throw new IllegalArgumentException("UI_PATCH_DUPLICATE_PATH");
            String value=text(file,"content"),encoding=file.path("encoding").asText("utf8");
            byte[] bytes=switch(encoding){case "utf8"->value.getBytes(StandardCharsets.UTF_8);case "base64"->Base64.getDecoder().decode(value);default->throw new IllegalArgumentException("UI_PATCH_ENCODING");};
            if(bytes.length>1_048_576)throw new IllegalArgumentException("UI_PATCH_FILE_BUDGET");
            String type=media(path);String source=new String(bytes,StandardCharsets.UTF_8);
            if(type.equals("text/javascript")&&!parser.inspect(source).accepted())throw new IllegalArgumentException("UI_PATCH_PREFLIGHT");
            if(type.equals("application/json"))JSON.readTree(bytes);
            if(type.equals("text/html"))for(var script:org.jsoup.Jsoup.parse(source).select("script:not([src])")){
                String scriptType=script.attr("type").strip().toLowerCase(Locale.ROOT);
                if((scriptType.isEmpty()||Set.of("module","text/javascript","application/javascript").contains(scriptType))&&!parser.inspect(script.data()).accepted())throw new IllegalArgumentException("UI_PATCH_PREFLIGHT");
            }
            var old=base.resources().get(path);if(old!=null&&old.side()==RuntimeResourceSide.SERVER)throw new IllegalArgumentException("UI_PATCH_RESOURCE_SIDE");
            var blob=store.put(bytes);resources.put(path,new RuntimeResourceRef(path,blob.sha256(),old==null?RuntimeResourceSide.CLIENT:old.side(),type,bytes.length));
        }
        if(root.has("delete")){
            if(!root.get("delete").isArray()||root.get("delete").size()>32)throw new IllegalArgumentException("UI_PATCH_DELETE");
            for(var value:root.get("delete")){if(!value.isTextual())throw new IllegalArgumentException("UI_PATCH_DELETE");String path=value.asText();path(path);if(!changed.add(path)||resources.remove(path)==null)throw new IllegalArgumentException("UI_PATCH_DELETE");}
        }
        if(resources.values().stream().filter(r->UiPatchPolicy.ui(r.path())).mapToLong(RuntimeResourceRef::size).sum()>8L*1024*1024)throw new IllegalArgumentException("UI_PATCH_TOTAL_BUDGET");
        for(var ref:resources.values())if(ref.path().startsWith("ui/")&&ref.mediaType().equals("text/html")){
            var document=org.jsoup.Jsoup.parse(new String(store.read(ref.sha256()),StandardCharsets.UTF_8));
            for(var element:document.select("script[src],link[rel=stylesheet][href],img[src]")){
                String link=element.attr(element.normalName().equals("link")?"href":"src");if(element.normalName().equals("img")&&link.startsWith("data:"))continue;
                java.net.URI resolved=java.net.URI.create(ref.path()).resolve(link).normalize();String path=resolved.getPath();
                if(resolved.isAbsolute()||resolved.getRawAuthority()!=null||path==null||!path.startsWith("ui/")||!resources.containsKey(path))throw new IllegalArgumentException("UI_PATCH_RESOURCE_LINK");
            }
        }
        var entries=new LinkedHashMap<String,RuntimeEntrypoint>();
        for(var entry:base.entrypoints().entrySet()){
            var e=entry.getValue();var resource=resources.get(e.path());if(resource==null)throw new IllegalArgumentException("UI_PATCH_ENTRYPOINT_DELETE");
            entries.put(entry.getKey(),new RuntimeEntrypoint(e.path(),e.side(),resource.sha256()));
        }
        var draft=new RuntimePackage(base.packageId(),base.type(),base.name(),base.version(),base.activationMode(),base.dependencies(),base.permissions(),entries,base.definitions(),resources,base.origin(),base.enabled(),base.revision()+1,"0".repeat(64),"",System.currentTimeMillis(),base.nativeCompatibility());
        FeedbackPackageContract.validate(draft,store::read);
        String hash=RuntimePackageCanonicalizer.sha256(draft);
        var candidate=new RuntimePackage(draft.packageId(),draft.type(),draft.name(),draft.version(),draft.activationMode(),draft.dependencies(),draft.permissions(),entries,draft.definitions(),resources,draft.origin(),draft.enabled(),draft.revision(),hash,Base64.getEncoder().encodeToString(signer.sign(hash.getBytes(StandardCharsets.US_ASCII))),draft.updatedAtEpochMillis(),draft.nativeCompatibility());
        UiPatchPolicy.require(base,candidate);return candidate;
    }
    private static void only(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.properties().stream().anyMatch(e->!fields.contains(e.getKey())))throw new IllegalArgumentException("UI_PATCH_FIELDS");}
    private static String text(JsonNode node,String key){if(!node.path(key).isTextual())throw new IllegalArgumentException("UI_PATCH_FIELD");return node.path(key).asText();}
    private static void path(String path){if(path==null||path.length()>256||!path.startsWith("ui/")||!path.matches("[A-Za-z0-9_@.-]+(?:/[A-Za-z0-9_@.-]+)*")||Arrays.stream(path.split("/")).anyMatch(p->p.equals(".")||p.equals("..")))throw new IllegalArgumentException("UI_PATCH_PATH");}
    private static String media(String path){String extension=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);return switch(extension){case "html"->"text/html";case "css"->"text/css";case "js","mjs"->"text/javascript";case "json"->"application/json";case "svg"->"image/svg+xml";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "webp"->"image/webp";case "gif"->"image/gif";case "woff"->"font/woff";case "woff2"->"font/woff2";default->throw new IllegalArgumentException("UI_PATCH_MEDIA");};}
}
