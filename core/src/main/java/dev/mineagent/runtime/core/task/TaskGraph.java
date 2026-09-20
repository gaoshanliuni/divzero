package dev.mineagent.runtime.core.task;

import dev.mineagent.runtime.api.task.TaskNodeStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TaskGraph {
    private final String title;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private long revision;

    public TaskGraph(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("task title must not be blank");
        }
        this.title = title;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized void addNode(String nodeId, Set<String> dependencies) {
        validateNodeId(nodeId);
        if (nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("task node already exists: " + nodeId);
        }
        ensureDependenciesExist(dependencies);
        nodes.put(nodeId, new Node(Set.copyOf(dependencies), TaskNodeStatus.PENDING));
        revision++;
    }

    public synchronized void replaceDependencies(String nodeId, Set<String> dependencies) {
        Node current = requireNode(nodeId);
        ensureDependenciesExist(dependencies);
        nodes.put(nodeId, new Node(Set.copyOf(dependencies), current.status()));
        if (containsCycle()) {
            nodes.put(nodeId, current);
            throw new IllegalArgumentException("task dependency cycle");
        }
        revision++;
    }

    public synchronized void transition(String nodeId, TaskNodeStatus status) {
        Node current = requireNode(nodeId);
        if (current.status() == status) {
            return;
        }
        nodes.put(nodeId, new Node(current.dependencies(), status));
        revision++;
    }

    public synchronized List<String> runnableNodeIds() {
        var runnable = new ArrayList<String>();
        nodes.forEach((nodeId, node) -> {
            if (node.status() == TaskNodeStatus.PENDING
                    && node.dependencies().stream().allMatch(this::isComplete)) {
                runnable.add(nodeId);
            }
        });
        return List.copyOf(runnable);
    }

    private boolean isComplete(String nodeId) {
        return requireNode(nodeId).status() == TaskNodeStatus.COMPLETED;
    }

    private Node requireNode(String nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("unknown task node: " + nodeId);
        }
        return node;
    }

    private void ensureDependenciesExist(Set<String> dependencies) {
        for (String dependency : dependencies) {
            if (!nodes.containsKey(dependency)) {
                throw new IllegalArgumentException("unknown dependency: " + dependency);
            }
        }
    }

    private boolean containsCycle() {
        var visiting = new HashSet<String>();
        var visited = new HashSet<String>();
        for (String nodeId : nodes.keySet()) {
            if (visit(nodeId, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean visit(String nodeId, Set<String> visiting, Set<String> visited) {
        if (visited.contains(nodeId)) {
            return false;
        }
        if (!visiting.add(nodeId)) {
            return true;
        }
        for (String dependency : nodes.get(nodeId).dependencies()) {
            if (visit(dependency, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    private static void validateNodeId(String nodeId) {
        if (nodeId == null || !nodeId.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid task node id");
        }
    }

    private record Node(Set<String> dependencies, TaskNodeStatus status) {
    }
}
