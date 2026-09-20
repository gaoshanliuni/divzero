package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Resolves only exact, owned, already-running Rhino publications; never installs or starts a dependency. */
public final class ServerScriptDependencies {
    private ServerScriptDependencies(){}
    public static ScriptDependencyGraph resolve(MinecraftServer server,UUID owner,UUID root,Map<UUID,String> required)throws Exception {
        if(!server.isSameThread())throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_SERVER_THREAD");
        var nodes=new LinkedHashMap<UUID,ScriptDependencyGraph.Node>();var visiting=new HashSet<UUID>();visiting.add(root);
        for(var edge:required.entrySet())visit(server,owner,edge.getKey(),edge.getValue(),nodes,visiting,0);
        var graph=new ScriptDependencyGraph(1,root,required,List.copyOf(nodes.values()));
        var journal=MineAgentRuntimeServices.codeDrafts(server).scriptPublications();
        for(var node:graph.nodes())graph.requireEmbedded(node,journal.get(node.publication()).dependencies());
        StudioScriptRuntime.requireDependencyGraph(server,graph);return graph;
    }
    private static void visit(MinecraftServer server,UUID owner,UUID id,String version,Map<UUID,ScriptDependencyGraph.Node> nodes,Set<UUID> visiting,int depth)throws Exception {
        if(depth>ScriptDependencyGraph.MAX_DEPTH||visiting.contains(id))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_CYCLE");
        if(nodes.containsKey(id)){if(!nodes.get(id).version().equals(version))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_VERSION");return;}
        if(nodes.size()>=ScriptDependencyGraph.MAX_NODES)throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_GRAPH_LIMIT");
        var runtime=ServerPackageRuntime.get(server);var head=runtime.worldLibrary().get(id).orElseThrow(()->new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_MISSING"));
        var pkg=runtime.ownedPackage(owner,id,head.revision()).orElseThrow(()->new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_NOT_OWNED"));
        if(!pkg.version().equals(version))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_VERSION");
        RuntimeStudioScriptPlan.source(pkg);
        if(!NativeCompatibilityPolicy.check(pkg,NativePackageCompatibility.observe(),"SERVER").allowed())throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_ENVIRONMENT");
        var records=MineAgentRuntimeServices.codeDrafts(server).scriptPublications().forPackage(owner,id).stream()
                .filter(r->r.state().equals("PUBLISHED")&&!r.uncertain()&&r.packageHash().equals(pkg.canonicalSha256())&&StudioScriptRuntime.dependencyAvailable(server,r)).toList();
        if(records.size()!=1)throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_NOT_LOADED");
        var r=records.getFirst();
        if(!r.sourceHash().equals(RuntimeStudioPlan.fingerprint(pkg)))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_SOURCE_CHANGED");
        var node=new ScriptDependencyGraph.Node(id,version,pkg.revision(),pkg.canonicalSha256(),r.id(),r.revision(),r.sourceHash(),RuntimeStudioScriptPlan.source(pkg).path(),pkg.dependencies());
        nodes.put(id,node);visiting.add(id);
        for(var edge:pkg.dependencies().entrySet())visit(server,owner,edge.getKey(),edge.getValue(),nodes,visiting,depth+1);
        visiting.remove(id);
    }
    public static boolean current(MinecraftServer server,UUID owner,ScriptDependencyGraph graph){try{return graph.equals(resolve(server,owner,graph.root(),graph.required()));}catch(Exception invalid){return false;}}
}
