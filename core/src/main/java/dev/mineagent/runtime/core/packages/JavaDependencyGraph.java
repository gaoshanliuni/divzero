package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import java.util.*;

/** Exact, already-running SERVER Java dependencies. This graph never installs or starts its nodes. */
public record JavaDependencyGraph(int schema,UUID root,Map<UUID,String> required,List<Node> nodes) {
    public static final int MAX_NODES=64,MAX_DEPTH=32;
    public record Node(UUID packageId,String version,long packageRevision,String canonical,UUID publication,long publicationRevision,
            String artifact,String sourceHash,String className,String nativeClasspath,Map<UUID,String> dependencies) {
        public Node {
            Objects.requireNonNull(packageId);Objects.requireNonNull(publication);dependencies=Collections.unmodifiableMap(new TreeMap<>(dependencies));
            if(version==null||!version.matches("[0-9A-Za-z_.+-]{1,64}")||packageRevision<1||publicationRevision<1||!sha(canonical)||!sha(artifact)||!sha(sourceHash)||!sha(nativeClasspath)
                    ||className==null||!className.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")||dependencies.size()>32)throw new IllegalArgumentException("JAVA_DEPENDENCY_NODE");
        }
    }
    public JavaDependencyGraph {
        Objects.requireNonNull(root);required=Collections.unmodifiableMap(new TreeMap<>(required));nodes=nodes.stream().sorted(Comparator.comparing(n->n.packageId().toString())).toList();
        if(schema!=1||required.size()>32||nodes.size()>MAX_NODES||nodes.stream().map(Node::packageId).distinct().count()!=nodes.size()||nodes.stream().map(Node::publication).distinct().count()!=nodes.size())throw new IllegalArgumentException("JAVA_DEPENDENCY_GRAPH");
        validate(root,required,nodes);
    }
    private static boolean sha(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    public static JavaDependencyGraph empty(UUID root){return new JavaDependencyGraph(1,root,Map.of(),List.of());}
    public void require(RuntimePackage pkg){if(!root.equals(pkg.packageId())||!required.equals(pkg.dependencies()))throw new IllegalStateException("JAVA_DEPENDENCY_SOURCE_CHANGED");}
    public String fingerprint(){try{return RuntimePackageCanonicalizer.sha256(new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(this));}catch(Exception e){throw new IllegalStateException("JAVA_DEPENDENCY_HASH");}}
    public String receiptHash(){return nodes.isEmpty()?"":fingerprint();}
    public JavaDependencyGraph embedded(Node node){
        var ids=new HashSet<UUID>();collect(node.dependencies().keySet(),ids);
        return new JavaDependencyGraph(1,node.packageId(),node.dependencies(),nodes.stream().filter(n->ids.contains(n.packageId())).toList());
    }
    private void collect(Set<UUID> next,Set<UUID> ids){for(var id:next)if(ids.add(id))collect(node(id).dependencies().keySet(),ids);}
    public Node node(UUID id){return nodes.stream().filter(n->n.packageId().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("JAVA_DEPENDENCY_MISSING"));}
    public List<UUID> scopes(){return nodes.stream().map(Node::publication).toList();}
    public void requireEmbedded(Node dependency,JavaDependencyGraph embedded){
        if(dependency.dependencies().isEmpty()){if(embedded!=null&&!embedded.nodes().isEmpty())throw new IllegalStateException("JAVA_DEPENDENCY_TRANSITIVE_CHANGED");return;}
        if(embedded==null||!embedded.root().equals(dependency.packageId())||!embedded.required().equals(dependency.dependencies()))throw new IllegalStateException("JAVA_DEPENDENCY_TRANSITIVE_CHANGED");
        for(var node:embedded.nodes())if(!node(node.packageId()).equals(node))throw new IllegalStateException("JAVA_DEPENDENCY_TRANSITIVE_CHANGED");
    }
    private static void validate(UUID root,Map<UUID,String> required,List<Node> nodes){
        var map=new HashMap<UUID,Node>();nodes.forEach(n->map.put(n.packageId(),n));var visited=new HashSet<UUID>();var visiting=new HashSet<UUID>();visiting.add(root);
        for(var edge:required.entrySet())visit(edge.getKey(),edge.getValue(),map,visited,visiting,0);
        if(visited.size()!=nodes.size())throw new IllegalArgumentException("JAVA_DEPENDENCY_EXTRA_NODE");
        var lengths=new HashMap<UUID,Integer>();for(var id:required.keySet())if(depth(id,map,lengths)>MAX_DEPTH)throw new IllegalArgumentException("JAVA_DEPENDENCY_DEPTH");
    }
    private static int depth(UUID id,Map<UUID,Node> nodes,Map<UUID,Integer> memo){
        var known=memo.get(id);if(known!=null)return known;int result=1;
        for(var next:nodes.get(id).dependencies().keySet())result=Math.max(result,1+depth(next,nodes,memo));memo.put(id,result);return result;
    }
    private static void visit(UUID id,String version,Map<UUID,Node> nodes,Set<UUID> visited,Set<UUID> visiting,int depth){
        if(depth>MAX_DEPTH||visiting.contains(id))throw new IllegalArgumentException("JAVA_DEPENDENCY_CYCLE");
        var node=nodes.get(id);if(node==null||!node.version().equals(version))throw new IllegalArgumentException("JAVA_DEPENDENCY_VERSION");
        if(!visited.add(id))return;visiting.add(id);for(var edge:node.dependencies().entrySet())visit(edge.getKey(),edge.getValue(),nodes,visited,visiting,depth+1);visiting.remove(id);
    }
}
