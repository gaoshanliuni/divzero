package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Existing trusted PLAYER history gate, current ownership and exact head fences on every version read. */
public final class ServerPackageCatalog {
    private static final ObjectMapper JSON=new ObjectMapper();private ServerPackageCatalog(){}
    public static Map<String,String> read(ServerPlayer viewer,Map<String,String> args)throws Exception{
        var server=viewer.level().getServer();var runtime=ServerPackageRuntime.get(server);var library=runtime.worldLibrary();String kind=args.get("kind");Object result;
        if(kind.equals("packages")){
            keys(args,Set.of("kind","search","offset"));int offset=number(args,"offset");var page=library.catalog(MineAgentRuntimeServices.worldId(server),viewer.getUUID(),args.get("search"),offset,8);var items=new ArrayList<Object>();
            for(UUID id:page.ids()){var head=library.get(id).orElse(null);if(head==null)items.add(Map.of("packageId",id,"state","QUARANTINED","reason",library.quarantined().getOrDefault(id,"UNAVAILABLE")));else{if(runtime.ownedPackage(viewer.getUUID(),id,head.revision()).isEmpty())throw new SecurityException("PACKAGE_CATALOG_CHANGED");items.add(runtime.headView(viewer.getUUID(),head));}}
            result=Map.of("items",items,"total",page.total(),"offset",offset,"nextOffset",page.nextOffset(),"more",page.more());
        }else if(kind.equals("operation")){
            keys(args,Set.of("kind","category","operation"));result=runtime.operationView(viewer,args.get("category"),UUID.fromString(args.get("operation")));
        }else{
            var common=new HashSet<>(Set.of("kind","packageId","headRevision","headHash"));if(!kind.equals("package"))common.add("offset");if(Set.of("version","versionText","versionFile").contains(kind)){common.add("versionRevision");common.add("snapshotHash");}if(kind.equals("versionFile"))common.add("path");keys(args,common);
            UUID id=UUID.fromString(args.get("packageId"));var head=library.get(id).orElseThrow(()->new IllegalStateException("PACKAGE_UNAVAILABLE"));
            if(runtime.ownedPackage(viewer.getUUID(),id,head.revision()).isEmpty())throw new SecurityException("PACKAGE_NOT_OWNED");
            boolean fresh=kind.equals("package")&&args.get("headRevision").equals("0")&&args.get("headHash").isEmpty();
            if(!fresh&&(head.revision()!=Long.parseLong(args.get("headRevision"))||!head.canonicalSha256().equals(args.get("headHash"))))throw new IllegalStateException("PACKAGE_CATALOG_STALE");
            if(kind.equals("package"))result=runtime.headView(viewer.getUUID(),head);
            else if(kind.equals("compatibility"))result=runtime.nativeCompatibility().inspect(viewer,head,number(args,"offset"));
            else if(kind.equals("versions")){var rows=library.versions(id,number(args,"offset"));var page=rows.stream().limit(8).toList();result=Map.of("versions",page,"offset",number(args,"offset"),"nextOffset",number(args,"offset")+page.size(),"more",rows.size()>8,"head",ServerPackageRuntime.headView(head));}
            else if(Set.of("version","versionText","versionFile").contains(kind)){
                var saved=library.version(id,Long.parseLong(args.get("versionRevision")),args.get("snapshotHash"));var manifest=saved.manifest();int offset=number(args,"offset");
                if(kind.equals("versionText"))result=slice(saved.payload(),offset);
                else if(kind.equals("version")){var versionView=new LinkedHashMap<>(ServerPackageRuntime.headView(manifest));versionView.put("state","ARCHIVED_VERSION");var all=manifest.resources().keySet().stream().sorted().toList();var files=all.stream().skip(offset).limit(8).map(path->{var ref=manifest.resources().get(path);return Map.of("path",path,"sha256",ref.sha256(),"bytes",ref.size(),"mediaType",ref.mediaType(),"side",ref.side());}).toList();result=Map.of("manifest",versionView,"snapshotHash",saved.payloadHash(),"provenance",saved.provenance(),"observedAt",saved.observedAt(),"files",files,"nextOffset",offset+files.size(),"more",offset+files.size()<all.size(),"resourceVerification","ON_EXPLICIT_FILE_READ_ONLY");}
                else{var ref=manifest.resources().get(args.get("path"));if(ref==null)throw new IllegalArgumentException("PACKAGE_VERSION_FILE");
                    if(!Set.of("text/html","text/css","text/javascript","application/javascript","application/json","text/plain").contains(ref.mediaType()))result=Map.of("text","[binary resource: metadata only; not executed or played]","offset",0,"nextOffset",0,"more",false,"length",0);
                    else{if(ref.size()>4*1024*1024)throw new IllegalStateException("PACKAGE_VERSION_TEXT_LIMIT");byte[] bytes;try(var in=java.nio.file.Files.newInputStream(runtime.worldContent().pathFor(ref.sha256()))){bytes=in.readNBytes(4*1024*1024+1);}if(bytes.length!=ref.size()||!ref.sha256().equals(dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes)))throw new IllegalStateException("PACKAGE_VERSION_RESOURCE_CHANGED");var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);result=slice(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(),offset);}
                }
            }else throw new IllegalArgumentException("PACKAGE_CATALOG_KIND");
        }
        String encoded=JSON.writeValueAsString(result);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("PACKAGE_CATALOG_RESPONSE_BUDGET");return Map.of("state",encoded,"executionMode","OWNER_PACKAGE_CATALOG_NO_MODEL_NO_RESTORE");
    }
    private static void keys(Map<String,String> args,Set<String> keys){if(!args.keySet().equals(keys))throw new IllegalArgumentException("PACKAGE_CATALOG_ARGUMENTS");}
    private static int number(Map<String,String> args,String key){int n=Integer.parseInt(args.get(key));if(n<0||n>32*1024*1024)throw new IllegalArgumentException("PACKAGE_CATALOG_OFFSET");return n;}
    private static Map<String,Object> slice(String text,int offset){int length=text.codePointCount(0,text.length());if(offset>length)throw new IllegalArgumentException("PACKAGE_CATALOG_OFFSET");int next=Math.min(length,offset+1024);return Map.of("text",text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,next)),"offset",offset,"nextOffset",next,"more",next<length,"length",length);}
}
