package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Resolves only exact, owned, already-running Java publications; never installs or starts a dependency. */
public final class ServerJavaDependencies {
    private ServerJavaDependencies(){}
    public static JavaDependencyGraph resolve(MinecraftServer server,UUID owner,UUID root,Map<UUID,String> required)throws Exception {
        if(!server.isSameThread())throw new IllegalStateException("JAVA_DEPENDENCY_SERVER_THREAD");
        var nodes=new LinkedHashMap<UUID,JavaDependencyGraph.Node>();var visiting=new HashSet<UUID>();visiting.add(root);
        for(var edge:required.entrySet())visit(server,owner,edge.getKey(),edge.getValue(),nodes,visiting,0);
        var graph=new JavaDependencyGraph(1,root,required,List.copyOf(nodes.values()));
        var journal=MineAgentRuntimeServices.codeDrafts(server).javaPublications();
        for(var node:graph.nodes())graph.requireEmbedded(node,journal.get(node.publication()).dependencies());
        return graph;
    }
    private static void visit(MinecraftServer server,UUID owner,UUID id,String version,Map<UUID,JavaDependencyGraph.Node> nodes,Set<UUID> visiting,int depth)throws Exception {
        if(depth>JavaDependencyGraph.MAX_DEPTH||visiting.contains(id))throw new IllegalStateException("JAVA_DEPENDENCY_CYCLE");
        if(nodes.containsKey(id)){if(!nodes.get(id).version().equals(version))throw new IllegalStateException("JAVA_DEPENDENCY_VERSION");return;}
        if(nodes.size()>=JavaDependencyGraph.MAX_NODES)throw new IllegalStateException("JAVA_DEPENDENCY_GRAPH_LIMIT");
        var runtime=ServerPackageRuntime.get(server);var head=runtime.worldLibrary().get(id).orElseThrow(()->new IllegalStateException("JAVA_DEPENDENCY_MISSING"));
        var pkg=runtime.ownedPackage(owner,id,head.revision()).orElseThrow(()->new IllegalStateException("JAVA_DEPENDENCY_NOT_OWNED"));
        if(!pkg.version().equals(version))throw new IllegalStateException("JAVA_DEPENDENCY_VERSION");
        RuntimeJavaPlan.source(pkg);
        if(!NativeCompatibilityPolicy.check(pkg,NativePackageCompatibility.observe(),"SERVER").allowed())throw new IllegalStateException("JAVA_DEPENDENCY_ENVIRONMENT");
        var manager=MineAgentRuntimeServices.javaExtensions(server);
        var records=MineAgentRuntimeServices.codeDrafts(server).javaPublications().forPackage(owner,id).stream()
                .filter(r->r.state().equals("PUBLISHED")&&!r.uncertain()&&r.runtimePackageHash().equals(pkg.canonicalSha256())&&manager.isLoaded(r.id())).toList();
        if(records.size()!=1)throw new IllegalStateException("JAVA_DEPENDENCY_NOT_LOADED");
        var r=records.getFirst();manager.requireLoaded(r.id(),r.artifact(),r.className());
        if(!r.sourceHash().equals(RuntimeStudioPlan.fingerprint(pkg)))throw new IllegalStateException("JAVA_DEPENDENCY_SOURCE_CHANGED");
        var node=new JavaDependencyGraph.Node(id,version,pkg.revision(),pkg.canonicalSha256(),r.id(),r.revision(),r.artifact(),r.sourceHash(),r.className(),r.nativeClasspath(),pkg.dependencies());
        nodes.put(id,node);visiting.add(id);
        for(var edge:pkg.dependencies().entrySet())visit(server,owner,edge.getKey(),edge.getValue(),nodes,visiting,depth+1);
        visiting.remove(id);
    }
    public static boolean current(MinecraftServer server,UUID owner,JavaDependencyGraph graph){try{return graph.equals(resolve(server,owner,graph.root(),graph.required()));}catch(Exception invalid){return false;}}
}
