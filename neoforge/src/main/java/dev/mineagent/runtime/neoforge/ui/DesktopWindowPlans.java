package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.core.ui.WindowPinPlan;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Bounded, one-shot model decisions. Applying/saving cosmetic state stays in the initiating trusted client. */
public final class DesktopWindowPlans implements AutoCloseable {
    private final MinecraftServer server;
    private final Map<UUID,Job> jobs=new LinkedHashMap<>();
    private static final class Job {
        final UUID id,agent;final ServerPlayer owner;final String fingerprint;final BooleanSupplier authority;
        final AtomicBoolean permit=new AtomicBoolean(true);final long expires=System.currentTimeMillis()+120000;
        String state="PLANNING",error="";Boolean pinned;
        Job(UUID id,UUID agent,ServerPlayer owner,String fingerprint,BooleanSupplier authority){this.id=id;this.agent=agent;this.owner=owner;this.fingerprint=fingerprint;this.authority=authority;}
    }
    public DesktopWindowPlans(MinecraftServer server){this.server=server;}
    private boolean current(Job job){try{return job.permit.get()&&System.currentTimeMillis()<job.expires&&server.getPlayerList().getPlayer(job.owner.getUUID())==job.owner&&job.authority.getAsBoolean()&&ServerTaskStart.allowed(job.owner,job.agent);}catch(RuntimeException unavailable){return false;}}
    private static Map<String,String> result(Job job){return Map.of("state",job.state,"errorCode",job.error,"pinned",job.pinned==null?"":job.pinned.toString());}
    public Map<String,String> handle(ServerPlayer viewer,String kind,UUID operation,Map<String,String> args,BooleanSupplier authority)throws Exception {
        if(!server.isSameThread())throw new IllegalStateException("WINDOW_PLAN_THREAD");
        tick();var old=jobs.get(operation);
        if(old!=null&&old.owner!=viewer)throw new SecurityException("WINDOW_PLAN_OWNER");
        if(kind.equals("read")||kind.equals("cancel")){
            if(old==null)throw new IllegalStateException("WINDOW_PLAN_NOT_FOUND");
            if(kind.equals("cancel")){old.permit.set(false);old.state="CANCELLED";}
            if(old.state.equals("READY")&&!current(old)){old.state="CANCELLED";old.permit.set(false);}
            return result(old);
        }
        if(!kind.equals("start"))throw new IllegalArgumentException("WINDOW_PLAN_KIND");
        UUID agent=UUID.fromString(args.get("agentId"));if(!authority.getAsBoolean()||!ServerTaskStart.allowed(viewer,agent))throw new SecurityException("WINDOW_PLAN_PERMISSION");
        if(!Set.of("true","false").contains(args.get("pinned")))throw new IllegalArgumentException("WINDOW_PLAN_INPUT");
        String prompt=WindowPinPlan.instructions(args.get("title"),Boolean.parseBoolean(args.get("pinned")),args.get("prompt"));
        String fingerprint=agent+"|"+prompt;
        if(old!=null){if(!old.fingerprint.equals(fingerprint))throw new IllegalArgumentException("WINDOW_PLAN_OPERATION_REUSED");return result(old);}
        if(jobs.size()>=128||jobs.values().stream().anyMatch(j->j.owner==viewer&&j.state.equals("PLANNING")))throw new IllegalStateException("WINDOW_PLAN_BUSY");
        var job=new Job(operation,agent,viewer,fingerprint,authority);jobs.put(operation,job);
        MineAgentRuntimeServices.worker(server).planPresentation(MineAgentRuntimeServices.config(server),operation,prompt,job.permit::get).whenComplete((response,failure)->server.execute(()->{
            if(!current(job)){job.state="CANCELLED";job.permit.set(false);return;}
            try{if(failure!=null||response==null||!response.type().equals("model.result"))throw new IllegalStateException("WINDOW_PLAN_MODEL_FAILED");job.pinned=WindowPinPlan.parse(String.valueOf(response.payload().get("text"))).pinned();job.state="READY";}
            catch(Exception e){job.state="FAILED";job.error="WINDOW_PLAN_MODEL_FAILED";job.permit.set(false);}
        }));
        return result(job);
    }
    public void tick(){for(var job:List.copyOf(jobs.values())){if(job.state.equals("PLANNING")&&!current(job)){job.state="CANCELLED";job.permit.set(false);}if(System.currentTimeMillis()>job.expires+180000){job.permit.set(false);jobs.remove(job.id);}}}
    public void disconnect(UUID owner){jobs.values().stream().filter(j->j.owner.getUUID().equals(owner)).forEach(j->{j.permit.set(false);j.state="CANCELLED";});}
    @Override public void close(){jobs.values().forEach(j->j.permit.set(false));jobs.clear();}
}
