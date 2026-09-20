package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.memory.PlayerPreferenceStore;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Owner-only personal data, including for operators. No preference grants any gameplay permission. */
public final class ServerPreferences {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ServerPreferences(){}
    public static void authorize(ServerPlayer viewer){var server=viewer.level().getServer();if(!server.isSameThread()||viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("PREFERENCE_IDENTITY");if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),dev.mineagent.runtime.api.permission.PermissionAction.CHAT))throw new SecurityException("PREFERENCE_PERMISSION");}
    private static UUID agent(ServerPlayer viewer,String text){if(text.isEmpty())return null;UUID id=UUID.fromString(text);if(MineAgentRuntimeServices.bodies(viewer.level().getServer()).definitions().stream().noneMatch(a->a.agentId().equals(id)))throw new IllegalArgumentException("PREFERENCE_AGENT_UNAVAILABLE");return id;}
    public static Map<String,Object> read(ServerPlayer viewer,Map<String,String> args)throws Exception {
        authorize(viewer);var server=viewer.level().getServer();var store=MineAgentRuntimeServices.preferences(server);String kind=args.get("kind");Object value;
        if(Set.of("list","uses").contains(kind)){
            if(!args.keySet().equals(Set.of("kind","offset")))throw new IllegalArgumentException("PREFERENCE_ARGUMENTS");int offset=Integer.parseInt(args.get("offset"));
            var page=kind.equals("list")?store.list(viewer.getUUID(),offset):store.uses(viewer.getUUID(),offset);var items=new ArrayList<Object>();
            for(var item:page.items()){items.add(item);if(JSON.writeValueAsBytes(items).length>20000){items.removeLast();break;}}
            value=Map.of("items",items,"offset",offset,"nextOffset",offset+items.size(),"more",offset+items.size()<page.total(),"total",page.total());
        }else if(kind.equals("receipt")){if(!args.keySet().equals(Set.of("kind","operationId")))throw new IllegalArgumentException("PREFERENCE_ARGUMENTS");value=store.receipt(viewer.getUUID(),UUID.fromString(args.get("operationId")));
        }else if(kind.equals("preview")){
            if(!args.keySet().equals(Set.of("kind","purpose","agentId")))throw new IllegalArgumentException("PREFERENCE_ARGUMENTS");var snapshot=store.snapshot(viewer.getUUID(),MineAgentRuntimeServices.worldId(server),agent(viewer,args.get("agentId")),args.get("purpose"));
            value=Map.of("refs",snapshot.refs(),"hash",snapshot.hash(),"section",snapshot.section(),"purpose",snapshot.purpose(),"world",snapshot.world(),"modelCalled",false);
        }else throw new IllegalArgumentException("PREFERENCE_ARGUMENTS");
        return Map.of("world",MineAgentRuntimeServices.worldId(server),"state",value,"policy","EXPLICIT_OWNER_PREFERENCES_NOT_WORLD_FACTS_OR_AUTHORITY");
    }
    public static PlayerPreferenceStore.Receipt write(ServerPlayer viewer,UUID operation,Map<String,String> args)throws Exception {
        authorize(viewer);if(!args.keySet().equals(Set.of("kind","action","id","expected","key","value","scope","agentId","purposes","enabled","confirmed"))||!"change".equals(args.get("kind"))||!"true".equals(args.get("confirmed"))||!Set.of("true","false").contains(args.get("enabled")))throw new IllegalArgumentException("PREFERENCE_CONFIRM_REQUIRED");
        Set<String> purposes=args.get("purposes").isEmpty()?Set.of():new HashSet<>(Arrays.asList(args.get("purposes").split(",",-1)));
        var input=new PlayerPreferenceStore.Input(operation,args.get("action"),args.get("id").isEmpty()?null:UUID.fromString(args.get("id")),Long.parseLong(args.get("expected")),args.get("key"),args.get("value"),args.get("scope"),agent(viewer,args.get("agentId")),purposes,Boolean.parseBoolean(args.get("enabled")));
        return MineAgentRuntimeServices.preferences(viewer.level().getServer()).change(viewer.getUUID(),MineAgentRuntimeServices.worldId(viewer.level().getServer()),input);
    }
    public static String error(Throwable error){for(int i=0;error!=null&&i<12;i++,error=error.getCause()){String value=Objects.toString(error.getMessage(),"");if(value.matches("PREFERENCE_[A-Z_]{1,80}"))return value;}return "PREFERENCE_FAILED";}
}
