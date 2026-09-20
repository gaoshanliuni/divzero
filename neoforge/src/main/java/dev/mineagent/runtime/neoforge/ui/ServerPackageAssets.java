package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.packages.PackageAssetMetadata.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Trusted Owner asset management. Never activates, runs, or copies runtime/world records. */
public final class ServerPackageAssets {
    private ServerPackageAssets(){}
    public static void authorize(ServerPlayer viewer){var server=viewer.level().getServer();if(!server.isSameThread()||viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("PACKAGE_ASSET_IDENTITY");if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.MANAGE_PACKAGES))throw new SecurityException("PACKAGE_ASSET_PERMISSION");}
    private static RuntimePackage owned(ServerPlayer viewer,UUID id,long revision,String hash){var p=ServerPackageRuntime.get(viewer.level().getServer()).ownedPackage(viewer.getUUID(),id,revision).orElseThrow(()->new SecurityException("PACKAGE_ASSET_NOT_OWNED"));if(!p.canonicalSha256().equals(hash))throw new IllegalStateException("PACKAGE_ASSET_SOURCE_CHANGED");return p;}
    public static Input input(ServerPlayer viewer,UUID operation,Map<String,String> args){
        authorize(viewer);if(!args.keySet().equals(Set.of("kind","action","packageId","packageRevision","canonical","name","shelfId","expectedRevision","confirmed"))||!"change".equals(args.get("kind"))||!"true".equals(args.get("confirmed")))throw new IllegalArgumentException("PACKAGE_ASSET_CONFIRM_REQUIRED");
        return new Input(operation,MineAgentRuntimeServices.worldId(viewer.level().getServer()),viewer.getUUID(),args.get("action"),UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),args.get("canonical"),args.get("name").strip(),args.get("shelfId").isEmpty()?null:UUID.fromString(args.get("shelfId")),Long.parseLong(args.get("expectedRevision")));
    }
    public static RuntimePackage source(ServerPlayer viewer,Input input)throws Exception {
        authorize(viewer);if(!input.owner().equals(viewer.getUUID())||!input.world().equals(MineAgentRuntimeServices.worldId(viewer.level().getServer())))throw new SecurityException("PACKAGE_ASSET_CONTEXT_CHANGED");
        if(input.shelf()==null)return owned(viewer,input.source(),input.sourceRevision(),input.sourceHash());
        var library=ServerPackageRuntime.get(viewer.level().getServer()).worldLibrary();var shelf=library.shelf(viewer.getUUID(),input.shelf());
        if(shelf.revision()!=input.expectedRevision()||!shelf.packageId().equals(input.source())||shelf.packageRevision()!=input.sourceRevision()||!shelf.canonical().equals(input.sourceHash())||input.action().equals("REUSE_ASSET")&&!shelf.active())throw new IllegalStateException("PACKAGE_ASSET_SHELF_STALE");return input.action().equals("WITHDRAW_ASSET")?null:library.assetSource(shelf);
    }
    public static Map<String,Object> read(ServerPlayer viewer,Map<String,String> args)throws Exception {
        authorize(viewer);var runtime=ServerPackageRuntime.get(viewer.level().getServer());var library=runtime.worldLibrary();String kind=args.get("kind");
        if(kind.equals("shelf")){if(!args.keySet().equals(Set.of("kind","active","offset"))||!Set.of("true","false").contains(args.get("active")))throw new IllegalArgumentException("PACKAGE_ASSET_ARGUMENTS");var page=library.shelves(viewer.getUUID(),Boolean.parseBoolean(args.get("active")),Integer.parseInt(args.get("offset")));var items=new ArrayList<Map<String,Object>>();for(var shelf:page.items()){var item=new ObjectMapper().convertValue(shelf,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});var alias=library.alias(viewer.getUUID(),shelf.packageId());item.put("displayName",alias.name().isEmpty()?shelf.sourceName():alias.name());items.add(item);}return Map.of("page",new Page<>(items,page.total(),page.offset(),page.nextOffset(),page.more()));}
        if(kind.equals("receipt")){if(!args.keySet().equals(Set.of("kind","operationId")))throw new IllegalArgumentException("PACKAGE_ASSET_ARGUMENTS");var receipt=library.assetReceipt(MineAgentRuntimeServices.worldId(viewer.level().getServer()),viewer.getUUID(),UUID.fromString(args.get("operationId")));if(receipt==null)throw new IllegalStateException("PACKAGE_ASSET_RECEIPT_MISSING");if(receipt.target()!=null&&Set.of("COPY","REUSE_ASSET").contains(receipt.input().action()))library.refreshAsset(receipt.target());return Map.of("receipt",receipt);}
        if(!kind.equals("inspect")||!args.keySet().equals(Set.of("kind","packageId","packageRevision","canonical")))throw new IllegalArgumentException("PACKAGE_ASSET_ARGUMENTS");
        var p=owned(viewer,UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),args.get("canonical"));var origin=library.derivation(viewer.getUUID(),p.packageId());return Map.of("head",runtime.headView(viewer.getUUID(),p),"alias",library.alias(viewer.getUUID(),p.packageId()),"derivation",origin==null?Map.of():origin,"originalName",p.name());
    }
    public static String code(Throwable failure){for(int i=0;failure!=null&&i<12;i++,failure=failure.getCause()){String value=Objects.toString(failure.getMessage(),"");if(value.matches("PACKAGE_[A-Z_]{1,80}")||Set.of("DEPENDENCY_MISSING","DEPENDENCY_VERSION_MISMATCH","DEPENDENCY_CYCLE").contains(value))return value;}return "PACKAGE_ASSET_FAILED";}
}
