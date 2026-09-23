package dev.mineagent.runtime.neoforge.client.host;
import dev.mineagent.runtime.neoforge.host.LocalHostCommands;
import dev.mineagent.runtime.core.host.HostCommandRequest;
import dev.mineagent.runtime.client.host.LocalPowerShellExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ClientHostCommands implements LocalHostCommands.Endpoint {
    private static final ClientHostCommands INSTANCE=new ClientHostCommands();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mineagent-local-powershell");t.setDaemon(true);return t;});
    private static final java.nio.file.Path PWSH=LocalPowerShellExecutor.discover().orElse(null);
    private volatile MinecraftServer server;private volatile UUID owner;private volatile Pending pending;private boolean installed;
    static final class Pending {final HostCommandRequest request;final MinecraftServer server;final UUID owner;final Object connection;final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();final AtomicBoolean live=new AtomicBoolean(true);final long expires=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);boolean started;Pending(HostCommandRequest r,MinecraftServer s,UUID o,Object c){request=r;server=s;owner=o;connection=c;}}
    public boolean matches(MinecraftServer s,UUID id){return server==s&&id.equals(owner);}
    public Map<String,Object> inspect(){return Map.of("available",PWSH!=null,"platform",System.getProperty("os.name","unknown"),"shell","PowerShell 7","localApprovalRequired",true,"scriptMaxChars",8192,"timeoutMaxSeconds",60,"outputBytesPerStream",LocalPowerShellExecutor.OUTPUT_BYTES,"workingDirectory","current Minecraft instance/mineagent-host/workspace","notFilesystemSandbox",true,"remoteServerAccess",false);}
    public CompletableFuture<Map<String,Object>> request(MinecraftServer s,UUID id,HostCommandRequest r){var result=new CompletableFuture<Map<String,Object>>();Minecraft.getInstance().execute(()->{var mc=Minecraft.getInstance();if(!matches(s,id)||mc.getSingleplayerServer()!=s||mc.player==null||!id.equals(mc.player.getUUID())||PWSH==null){result.complete(Map.of("status","REJECTED","error","HOST_UNAVAILABLE_OR_CONTEXT_CHANGED"));return;}if(pending!=null){result.complete(Map.of("status","REJECTED","error","HOST_COMMAND_BUSY"));return;}var p=new Pending(r,s,id,mc.getConnection());pending=p;p.result.whenComplete((v,e)->{if(e!=null)result.complete(Map.of("status","UNKNOWN","error","HOST_RESULT_UNAVAILABLE"));else result.complete(v);});mc.setScreen(new HostCommandScreen(mc.screen,p));});return result;}
    public void cancel(UUID operation){Minecraft.getInstance().execute(()->{var p=pending;if(p!=null&&p.request.operation().equals(operation))cancel(p,"HOST_CONVERSATION_CANCELLED");});}
    static void cancel(Pending p){cancel(p,"HOST_USER_CANCELLED_NOT_EXECUTED");}
    private static void cancel(Pending p,String reason){p.live.set(false);if(!p.started)finish(p,Map.of("status","REJECTED","error",reason));}
    static void approve(Pending p){var mc=Minecraft.getInstance();if(INSTANCE.pending!=p||p.started)return;if(System.nanoTime()>p.expires){cancel(p,"HOST_APPROVAL_EXPIRED");return;}if(!p.live.get()||mc.getConnection()!=p.connection||mc.getSingleplayerServer()!=p.server||mc.player==null||!p.owner.equals(mc.player.getUUID())){cancel(p,"HOST_CONTEXT_CHANGED");return;}try{var executor=new LocalPowerShellExecutor(mc.gameDirectory.toPath(),PWSH);p.started=true;IO.submit(()->{var result=executor.execute(p.request,p.request.sha256(),p.live::get);mc.execute(()->finish(p,result));});}catch(Exception e){finish(p,Map.of("status","REJECTED","error","HOST_EXECUTOR_UNAVAILABLE"));}}
    private static void finish(Pending p,Map<String,Object> result){if(INSTANCE.pending==p)INSTANCE.pending=null;p.result.complete(result);var mc=Minecraft.getInstance();if(mc.screen instanceof HostCommandScreen screen&&screen.pending()==p)screen.completed(result);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();var self=INSTANCE;if(!self.installed){self.installed=true;LocalHostCommands.install(self);Runtime.getRuntime().addShutdownHook(new Thread(()->{var active=self.pending;if(active!=null)active.live.set(false);IO.shutdownNow();try{IO.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}},"mineagent-host-stop"));}self.server=mc.getSingleplayerServer();self.owner=mc.player==null?null:mc.player.getUUID();var p=self.pending;if(p==null)return;if(p.server!=self.server||!p.owner.equals(self.owner)||p.connection!=mc.getConnection()||!(mc.screen instanceof HostCommandScreen screen)||screen.pending()!=p)cancel(p,"HOST_CONTEXT_CHANGED");else if(!p.started&&System.nanoTime()>p.expires)cancel(p,"HOST_APPROVAL_EXPIRED");}
}
