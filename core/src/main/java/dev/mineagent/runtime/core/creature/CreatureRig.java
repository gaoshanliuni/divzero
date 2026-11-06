package dev.mineagent.runtime.core.creature;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import java.util.*;

/** Rigid hierarchical bones. Mesh coordinates are local to each joint; no collision mutation. */
public final class CreatureRig {
    public record Bone(String name, String parent, List<Double> pivot, RuntimeMesh mesh) {}
    public static List<Bone> parse(JsonNode node) {
        if (node == null || node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() < 1 || node.size() > 32) throw new IllegalArgumentException("CREATURE_RIG_BONES");
        var names = new HashSet<String>();
        var out = new ArrayList<Bone>();
        int vertices = 0, triangles = 0;
        for (var n : node) {
            if (!n.isObject() || !Set.of("name", "parent", "pivot", "model").containsAll(n.properties().stream().map(Map.Entry::getKey).toList())) throw new IllegalArgumentException("CREATURE_RIG_FIELDS");
            String name = n.path("name").asText(""), parent = n.path("parent").asText("");
            if (!name.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}") || names.contains(name) || !parent.isEmpty() && !names.contains(parent)) throw new IllegalArgumentException("CREATURE_RIG_PARENT_ORDER");
            var pivot = new ArrayList<Double>();
            if (!n.path("pivot").isArray() || n.path("pivot").size() != 3) throw new IllegalArgumentException("CREATURE_RIG_PIVOT");
            for (var x : n.path("pivot")) {
                double v=x.asDouble();
                if (!x.isNumber() || !Double.isFinite(v) || Math.abs(v)>RuntimeMesh.MAX_AXIS) throw new IllegalArgumentException("CREATURE_RIG_PIVOT");
                pivot.add(v);
            }
            RuntimeMesh mesh = n.has("model") ? RuntimeMesh.parse(n.get("model").toString()) : null;
            if (mesh != null) {
                if (mesh.physics().dynamic()) throw new IllegalArgumentException("CREATURE_RIG_MODEL");
                vertices+=mesh.vertices().size();triangles+=mesh.triangles().size();
                if(vertices>RuntimeMesh.MAX_VERTICES || triangles>RuntimeMesh.MAX_TRIANGLES) throw new IllegalArgumentException("CREATURE_RIG_BUDGET");
            }
            names.add(name);out.add(new Bone(name,parent,List.copyOf(pivot),mesh));
        }
        var origins=new HashMap<String,double[]>();double[] low={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},high={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var bone:out){var parent=origins.getOrDefault(bone.parent(),new double[3]);double[] origin={parent[0]+bone.pivot().get(0),parent[1]+bone.pivot().get(1),parent[2]+bone.pivot().get(2)};origins.put(bone.name(),origin);
            if(bone.mesh()!=null)for(var v:bone.mesh().vertices()){double[] xyz={origin[0]+v.x(),origin[1]+v.y(),origin[2]+v.z()};for(int axis=0;axis<3;axis++){low[axis]=Math.min(low[axis],xyz[axis]);high[axis]=Math.max(high[axis],xyz[axis]);if(Math.abs(xyz[axis])>RuntimeMesh.MAX_AXIS||high[axis]-low[axis]>RuntimeMesh.MAX_AXIS+1e-5)throw new IllegalArgumentException("CREATURE_RIG_BOUNDS at bones."+bone.name()+": combined rest-pose extent exceeds 100 blocks");}}
        }
        return List.copyOf(out);
    }
    public static void validateTracks(List<Bone> bones, Map<String,CreatureAnimation.Clip> clips) {
        var names=new HashSet<String>();bones.forEach(b->names.add(b.name()));
        for(var clip:clips.values()) if(!names.containsAll(clip.bones().keySet())) throw new IllegalArgumentException("CREATURE_ANIMATION_UNKNOWN_BONE");
    }
    private CreatureRig() {}
}
