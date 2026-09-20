package dev.mineagent.runtime.core.packages;

import java.util.*;

/** Exact already-running mixed Rhino/Java CLIENT dependency closure. It never installs or starts nodes. */
public record ClientDependencyGraph(int schema,UUID root,Map<UUID,String> required,List<Node> nodes) {
    public static final int MAX_NODES=64,MAX_DEPTH=32;
    public enum Language {RHINO,JAVA}
    public record Node(UUID packageId,String version,long packageRevision,String canonical,UUID runtime,long runtimeRevision,
            Language language,String sourceHash,String runtimeHash,String entry,Map<UUID,String> dependencies) {
        public Node {Objects.requireNonNull(packageId);Objects.requireNonNull(runtime);Objects.requireNonNull(language);dependencies=Collections.unmodifiableMap(new TreeMap<>(dependencies));if(version==null||!version.matches("[0-9A-Za-z_.+-]{1,64}")||packageRevision<1||runtimeRevision<1||!sha(canonical)||!sha(sourceHash)||!sha(runtimeHash)||entry==null||entry.length()>256||dependencies.size()>32)throw new IllegalArgumentException("CLIENT_DEPENDENCY_NODE");if(language==Language.RHINO&&!ClientScriptPlan.path(entry)||language==Language.JAVA&&!entry.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}"))throw new IllegalArgumentException("CLIENT_DEPENDENCY_ENTRY");}
    }
    public ClientDependencyGraph {Objects.requireNonNull(root);required=Collections.unmodifiableMap(new TreeMap<>(required));nodes=nodes.stream().sorted(Comparator.comparing(n->n.packageId().toString())).toList();if(schema!=1||required.size()>32||nodes.size()>MAX_NODES||nodes.stream().map(Node::packageId).distinct().count()!=nodes.size()||nodes.stream().map(Node::runtime).distinct().count()!=nodes.size())throw new IllegalArgumentException("CLIENT_DEPENDENCY_GRAPH");validate(root,required,nodes);}
    private static boolean sha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    public static ClientDependencyGraph empty(UUID root){return new ClientDependencyGraph(1,root,Map.of(),List.of());}
    public Node node(UUID id){return nodes.stream().filter(n->n.packageId().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("CLIENT_DEPENDENCY_MISSING"));}
    public List<UUID> scopes(){return nodes.stream().map(Node::runtime).toList();}
    public ClientDependencyGraph embedded(Node node){var ids=new HashSet<UUID>();collect(node.dependencies().keySet(),ids);return new ClientDependencyGraph(1,node.packageId(),node.dependencies(),nodes.stream().filter(n->ids.contains(n.packageId())).toList());}
    private void collect(Set<UUID> next,Set<UUID> ids){for(var id:next)if(ids.add(id))collect(node(id).dependencies().keySet(),ids);}
    public void requireEmbedded(Node dependency,ClientDependencyGraph embedded){if(embedded==null||!embedded.root().equals(dependency.packageId())||!embedded.required().equals(dependency.dependencies()))throw new IllegalStateException("CLIENT_DEPENDENCY_TRANSITIVE_CHANGED");for(var node:embedded.nodes())if(!node(node.packageId()).equals(node))throw new IllegalStateException("CLIENT_DEPENDENCY_TRANSITIVE_CHANGED");}
    public String receiptHash(){if(nodes.isEmpty())return "";try{return RuntimePackageCanonicalizer.sha256(new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(this));}catch(Exception failure){throw new IllegalStateException("CLIENT_DEPENDENCY_HASH",failure);}}
    private static void validate(UUID root,Map<UUID,String> required,List<Node> nodes){var map=new HashMap<UUID,Node>();nodes.forEach(n->map.put(n.packageId(),n));var visited=new HashSet<UUID>();var visiting=new HashSet<UUID>();visiting.add(root);for(var edge:required.entrySet())visit(edge.getKey(),edge.getValue(),map,visited,visiting,0);if(visited.size()!=nodes.size())throw new IllegalArgumentException("CLIENT_DEPENDENCY_EXTRA_NODE");var lengths=new HashMap<UUID,Integer>();for(var id:required.keySet())if(depth(id,map,lengths)>MAX_DEPTH)throw new IllegalArgumentException("CLIENT_DEPENDENCY_DEPTH");}
    private static int depth(UUID id,Map<UUID,Node> nodes,Map<UUID,Integer> memo){var known=memo.get(id);if(known!=null)return known;int result=1;for(var next:nodes.get(id).dependencies().keySet())result=Math.max(result,1+depth(next,nodes,memo));memo.put(id,result);return result;}
    private static void visit(UUID id,String version,Map<UUID,Node> nodes,Set<UUID> visited,Set<UUID> visiting,int depth){if(depth>MAX_DEPTH||visiting.contains(id))throw new IllegalArgumentException("CLIENT_DEPENDENCY_CYCLE");var node=nodes.get(id);if(node==null||!node.version().equals(version))throw new IllegalArgumentException("CLIENT_DEPENDENCY_VERSION");if(!visited.add(id))return;visiting.add(id);for(var edge:node.dependencies().entrySet())visit(edge.getKey(),edge.getValue(),nodes,visited,visiting,depth+1);visiting.remove(id);}
}
