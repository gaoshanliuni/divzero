package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Declarative dependencies between ordinary skills; never an alternate executor or permission source. */
public record CooperationPlan(String name,List<Node> nodes,List<Supply> supplies) {
    public record Node(String id,UUID agent,ObjectNode intent,List<String> after) {
        public Node { Objects.requireNonNull(agent);intent=intent.deepCopy();after=List.copyOf(after); }
        @Override public ObjectNode intent(){return intent.deepCopy();}
        public SkillSpec spec(){return SkillSpec.parse(intent,null);}
    }
    public record Supply(String gather,String handoff,String construction){}
    public CooperationPlan { nodes=List.copyOf(nodes);supplies=List.copyOf(supplies);validate(name,nodes,supplies); }
    public Set<UUID> agents(){var result=new LinkedHashSet<UUID>();for(var n:nodes)result.add(n.agent());return Set.copyOf(result);}
    public Node node(String id){return nodes.stream().filter(n->n.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("COOP_NODE_MISSING "+id));}
    public static CooperationPlan parse(JsonNode root){
        fields(root,Set.of("name","nodes","supplies"),"plan");
        if(!root.path("name").isTextual()||!root.path("nodes").isArray()||root.has("supplies")&&!root.path("supplies").isArray())throw new IllegalArgumentException("COOP_PLAN_FIELDS");
        var nodes=new ArrayList<Node>();int index=0;
        for(var n:root.path("nodes")){
            String path="nodes["+(index++)+"]";fields(n,Set.of("id","agent","intent","after"),path);
            if(!n.path("id").isTextual()||!n.path("agent").isTextual()||!n.path("intent").isObject()||n.has("after")&&!n.path("after").isArray())throw new IllegalArgumentException(path+": expected id, agent UUID, intent object and optional after array");
            var after=new ArrayList<String>();for(var dependency:n.path("after")){if(!dependency.isTextual())throw new IllegalArgumentException(path+".after: expected node IDs");after.add(dependency.asText());}
            try{nodes.add(new Node(n.path("id").asText(),UUID.fromString(n.path("agent").asText()),(ObjectNode)n.path("intent"),after));}catch(IllegalArgumentException invalid){throw new IllegalArgumentException(path+": "+invalid.getMessage(),invalid);}
        }
        var supplies=new ArrayList<Supply>();for(var n:root.path("supplies")){fields(n,Set.of("gather","handoff","construction"),"supplies");for(String key:List.of("gather","handoff","construction"))if(!n.path(key).isTextual())throw new IllegalArgumentException("supplies."+key+": expected node ID");supplies.add(new Supply(n.path("gather").asText(),n.path("handoff").asText(),n.path("construction").asText()));}
        return new CooperationPlan(root.path("name").asText(),nodes,supplies);
    }
    private static void fields(JsonNode n,Set<String> allowed,String path){if(!n.isObject())throw new IllegalArgumentException(path+": expected object");for(var f:n.properties())if(!allowed.contains(f.getKey()))throw new IllegalArgumentException(path+"."+f.getKey()+": unknown field");}
    private static void validate(String name,List<Node> nodes,List<Supply> supplies){
        if(name==null||name.isBlank()||name.length()>128||nodes.isEmpty()||nodes.size()>128||supplies.size()>128)throw new IllegalArgumentException("COOP_PLAN_SIZE");
        var index=new LinkedHashMap<String,Node>();
        for(var node:nodes){
            if(node.id()==null||!node.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||index.putIfAbsent(node.id(),node)!=null)throw new IllegalArgumentException("COOP_DUPLICATE_OR_INVALID_NODE "+node.id());
            SkillSpec spec;try{spec=node.spec();}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("nodes."+node.id()+".intent: "+invalid.getMessage(),invalid);}
            if(!spec.actor().equals("ai")||!Set.of(SkillSpec.Kind.GATHER,SkillSpec.Kind.HANDOFF,SkillSpec.Kind.CONSTRUCT).contains(spec.kind())||spec.resumePrevious()||spec.allowTeleport())throw new IllegalArgumentException("nodes."+node.id()+".intent: cooperative steps require finite GATHER, HANDOFF or CONSTRUCT using AI bodies");
            if(!node.intent().path("expected_revision").isIntegralNumber()||node.intent().path("expected_revision").asLong()!=0)throw new IllegalArgumentException("nodes."+node.id()+".intent.expected_revision: expected 0");
        }
        var complete=new HashSet<String>();while(complete.size()<nodes.size()){int before=complete.size();for(var node:nodes){for(var dep:node.after())if(!index.containsKey(dep))throw new IllegalArgumentException("nodes."+node.id()+".after: unknown node "+dep);if(complete.containsAll(node.after()))complete.add(node.id());}if(complete.size()==before)throw new IllegalArgumentException("COOP_DEPENDENCY_CYCLE");}
        var supplied=new HashSet<String>();for(var supply:supplies){
            var gather=index.get(supply.gather());var handoff=index.get(supply.handoff());var construction=index.get(supply.construction());
            if(gather==null||handoff==null||construction==null||gather.spec().kind()!=SkillSpec.Kind.GATHER||handoff.spec().kind()!=SkillSpec.Kind.HANDOFF||construction.spec().kind()!=SkillSpec.Kind.CONSTRUCT||!gather.agent().equals(handoff.agent())||gather.agent().equals(construction.agent())||!gather.spec().crop().equals(handoff.spec().crop())||gather.spec().limit()<handoff.spec().limit()||!ancestors(handoff,index).contains(gather.id())||!ancestors(construction,index).contains(handoff.id())||!supplied.add(construction.id()+"/"+gather.spec().crop()))throw new IllegalArgumentException("COOP_SUPPLY_LINK_INVALID "+supply);
        }
    }
    private static Set<String> ancestors(Node node,Map<String,Node> index){var result=new HashSet<String>();var pending=new ArrayDeque<>(node.after());while(!pending.isEmpty()){String id=pending.removeFirst();if(result.add(id))pending.addAll(index.get(id).after());}return result;}
}
