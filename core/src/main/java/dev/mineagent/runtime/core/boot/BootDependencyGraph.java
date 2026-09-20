package dev.mineagent.runtime.core.boot;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import java.util.*;

/** Exact artifact graph, not mutable library-head aliases or installation authority. */
public record BootDependencyGraph(int schema,List<Node> nodes) {
    public static final int MAX_NODES=64,MAX_DEPTH=32;
    public record Node(UUID packageId,String version,String canonical,String artifact,String modId,String filename,String publicKey,Map<UUID,String> dependencies){
        public Node {
            Objects.requireNonNull(packageId);dependencies=Collections.unmodifiableMap(new TreeMap<>(dependencies));
            if(version==null||!version.matches("[0-9A-Za-z_.+-]{1,64}")||canonical==null||!canonical.matches("[a-f0-9]{64}")||artifact==null||!artifact.matches("[a-f0-9]{64}")||modId==null||!modId.matches("[a-z][a-z0-9_]{1,63}")||filename==null||!filename.matches("mineagent-boot-"+packageId+"-[a-f0-9]{64}\\.jar")||publicKey==null||publicKey.length()>4096||dependencies.size()>MAX_NODES)throw new IllegalArgumentException("BOOT_DEPENDENCY_NODE");
        }
        public void require(BootArtifact.Metadata meta,String hash){
            var pkg=meta.manifest();if(!pkg.packageId().equals(packageId)||!pkg.version().equals(version)||!pkg.canonicalSha256().equals(canonical)||!hash.equals(artifact)||!meta.modId().equals(modId)||!pkg.dependencies().equals(dependencies))throw new IllegalStateException("BOOT_DEPENDENCY_CHANGED");
        }
    }
    public BootDependencyGraph {nodes=nodes.stream().sorted(Comparator.comparing(n->n.packageId().toString())).toList();if(schema!=1||nodes.size()>MAX_NODES||nodes.stream().map(Node::packageId).distinct().count()!=nodes.size()||nodes.stream().map(Node::modId).distinct().count()!=nodes.size())throw new IllegalArgumentException("BOOT_DEPENDENCY_GRAPH");}
    public static BootDependencyGraph empty(){return new BootDependencyGraph(1,List.of());}
    public Node node(UUID id){return nodes.stream().filter(n->n.packageId().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("BOOT_DEPENDENCY_UNRESOLVED"));}
    public void require(RuntimePackage root){
        var byId=new LinkedHashMap<UUID,Node>();for(var n:nodes)byId.put(n.packageId(),n);
        var reached=new HashSet<UUID>();var visiting=new HashSet<UUID>();visiting.add(root.packageId());
        for(var dependency:root.dependencies().entrySet())visit(dependency.getKey(),dependency.getValue(),byId,reached,visiting,0);
        if(reached.size()!=nodes.size())throw new IllegalStateException("BOOT_DEPENDENCY_EXTRA_NODE");
    }
    private static void visit(UUID id,String version,Map<UUID,Node> nodes,Set<UUID> reached,Set<UUID> visiting,int depth){
        if(depth>MAX_DEPTH||visiting.contains(id))throw new IllegalStateException("BOOT_DEPENDENCY_CYCLE_OR_DEPTH");var n=nodes.get(id);
        if(n==null||!n.version().equals(version))throw new IllegalStateException("BOOT_DEPENDENCY_VERSION");if(!reached.add(id))return;
        visiting.add(id);for(var child:n.dependencies().entrySet())visit(child.getKey(),child.getValue(),nodes,reached,visiting,depth+1);visiting.remove(id);
    }
    public void requireEmbedded(BootArtifact.Metadata artifact){
        var embedded=artifact.dependencies()==null?empty():artifact.dependencies();embedded.require(artifact.manifest());
        for(var n:embedded.nodes())if(!node(n.packageId()).equals(n))throw new IllegalStateException("BOOT_DEPENDENCY_TRANSITIVE_CHANGED");
    }
}
