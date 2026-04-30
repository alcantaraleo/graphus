package io.graphus.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CallGraph {

    private final Map<String, SymbolNode> nodes = new LinkedHashMap<>();
    private final Set<CallEdge> edges = new LinkedHashSet<>();

    public void addNode(SymbolNode node) {
        if (node != null) {
            nodes.put(node.getId(), node);
        }
    }

    public SymbolNode getNode(String id) {
        return nodes.get(id);
    }

    public Collection<SymbolNode> getNodes() {
        return List.copyOf(nodes.values());
    }

    public Set<CallEdge> getEdges() {
        return Set.copyOf(edges);
    }

    public void addEdge(String fromId, String toId) {
        if (fromId == null || toId == null || fromId.isBlank() || toId.isBlank()) {
            return;
        }

        edges.add(new CallEdge(fromId, toId));

        SymbolNode from = nodes.get(fromId);
        SymbolNode to = nodes.get(toId);

        if (from instanceof MethodNode fromMethod) {
            fromMethod.addCallee(toId);
        }
        if (to instanceof MethodNode toMethod) {
            toMethod.addCaller(fromId);
        }
    }

    public Set<String> incomingNeighbors(String nodeId) {
        Set<String> incoming = new LinkedHashSet<>();
        for (CallEdge edge : edges) {
            if (edge.toId().equals(nodeId)) {
                incoming.add(edge.fromId());
            }
        }
        return incoming;
    }

    public Set<String> outgoingNeighbors(String nodeId) {
        Set<String> outgoing = new LinkedHashSet<>();
        for (CallEdge edge : edges) {
            if (edge.fromId().equals(nodeId)) {
                outgoing.add(edge.toId());
            }
        }
        return outgoing;
    }

    public List<String> blastRadiusCallers(String targetNodeId, int depth) {
        return traverseReverse(targetNodeId, depth);
    }

    public List<String> blastRadiusCallees(String sourceNodeId, int depth) {
        return traverseForward(sourceNodeId, depth);
    }

    private List<String> traverseReverse(String startNodeId, int depth) {
        return bfs(startNodeId, depth, false);
    }

    private List<String> traverseForward(String startNodeId, int depth) {
        return bfs(startNodeId, depth, true);
    }

    private List<String> bfs(String startNodeId, int maxDepth, boolean forward) {
        if (startNodeId == null || startNodeId.isBlank()) {
            return List.of();
        }

        int normalizedDepth = Math.max(maxDepth, 0);
        Set<String> visited = new LinkedHashSet<>();
        Deque<TraversalState> queue = new ArrayDeque<>();
        List<String> result = new ArrayList<>();

        visited.add(startNodeId);
        queue.add(new TraversalState(startNodeId, 0));

        while (!queue.isEmpty()) {
            TraversalState state = queue.removeFirst();
            if (state.depth() == normalizedDepth) {
                continue;
            }

            Set<String> neighbors = forward
                    ? outgoingNeighbors(state.nodeId())
                    : incomingNeighbors(state.nodeId());

            for (String neighbor : neighbors) {
                if (visited.add(neighbor)) {
                    result.add(neighbor);
                    queue.addLast(new TraversalState(neighbor, state.depth() + 1));
                }
            }
        }
        return result;
    }

    private record TraversalState(String nodeId, int depth) {
    }
}
