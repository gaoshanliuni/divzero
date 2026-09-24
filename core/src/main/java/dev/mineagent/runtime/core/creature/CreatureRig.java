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
                if (!x.isNumber() || !Double.isFinite(v) || Math.abs(v)>16) throw new IllegalArgumentException("CREATURE_RIG_PIVOT");
                pivot.add(v);
            }
            RuntimeMesh mesh = n.has("model") ? RuntimeMesh.parse(n.get("model").toString()) : null;
            if (mesh != null) {
                if (!mesh.texture().isEmpty() || mesh.physics().dynamic()) throw new IllegalArgumentException("CREATURE_RIG_MODEL");
                vertices+=mesh.vertices().size();triangles+=mesh.triangles().size();
                if(vertices>RuntimeMesh.MAX_VERTICES || triangles>RuntimeMesh.MAX_TRIANGLES) throw new IllegalArgumentException("CREATURE_RIG_BUDGET");
            }
            names.add(name);out.add(new Bone(name,parent,List.copyOf(pivot),mesh));
        }
        return List.copyOf(out);
    }
    public static void validateTracks(List<Bone> bones, Map<String,CreatureAnimation.Clip> clips) {
        var names=new HashSet<String>();bones.forEach(b->names.add(b.name()));
        for(var clip:clips.values()) if(!names.containsAll(clip.bones().keySet())) throw new IllegalArgumentException("CREATURE_ANIMATION_UNKNOWN_BONE");
    }
    private CreatureRig() {}
}
