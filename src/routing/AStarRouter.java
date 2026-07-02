package src.routing;

import src.model.CrisisGraph;
import src.model.Edge;
import src.model.Node;

import java.util.*;

public class AStarRouter {

    private final CrisisGraph graph;
    private final Map<String, List<Edge>> adjacency = new HashMap<>();

    public AStarRouter(CrisisGraph graph) {
        this.graph = graph;
        buildAdjacencyList();
    }

    private void buildAdjacencyList() {
        for (Edge edge : graph.getAllEdges().values()) {
            adjacency.computeIfAbsent(edge.getSource().getId(), k -> new ArrayList<>()).add(edge);
        }
    }

    public List<Edge> findShortestRoute(Node start, Node destination, double alpha, double beta) {
        if (start == null || destination == null) {
            return new ArrayList<>();
        }

        Map<String, Double> gScore = new HashMap<>();
        Map<String, Edge> previousEdge = new HashMap<>();

        for (String nodeId : graph.getAllNodes().keySet()) {
            gScore.put(nodeId, Double.POSITIVE_INFINITY);
        }

        gScore.put(start.getId(), 0.0);

        PriorityQueue<NodeCost> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        openSet.add(new NodeCost(start.getId(), heuristic(start, destination), 0.0));

        while (!openSet.isEmpty()) {
            NodeCost current = openSet.poll();

            if (current.nodeId.equals(destination.getId())) {
                break;
            }

            if (current.gCost > gScore.getOrDefault(current.nodeId, Double.POSITIVE_INFINITY)) {
                continue;
            }

            for (Edge edge : adjacency.getOrDefault(current.nodeId, List.of())) {
                if (!edge.isPassable()) continue;

                double weight = edge.getDynamicWeight(alpha, beta);
                if (Double.isInfinite(weight) || Double.isNaN(weight)) continue;

                String nextId = edge.getTarget().getId();
                double tentativeG = gScore.get(current.nodeId) + weight;

                if (tentativeG < gScore.getOrDefault(nextId, Double.POSITIVE_INFINITY)) {
                    gScore.put(nextId, tentativeG);
                    previousEdge.put(nextId, edge);

                    Node nextNode = edge.getTarget();
                    double h = heuristic(nextNode, destination);
                    double f = tentativeG + h;

                    openSet.add(new NodeCost(nextId, f, tentativeG));
                }
            }
        }

        if (!previousEdge.containsKey(destination.getId())) {
            System.out.println("[⚠️ A*] ไม่พบเส้นทางไปถึงปลายทาง");
            return new ArrayList<>();
        }

        List<Edge> route = reconstructRoute(start, destination, previousEdge);

        System.out.println("[✓ A*] พบเส้นทางดีที่สุด จำนวนถนน "
                + route.size()
                + " เส้น");

        return route;
    }

    private List<Edge> reconstructRoute(Node start, Node destination, Map<String, Edge> previousEdge) {
        List<Edge> route = new ArrayList<>();
        String currentId = destination.getId();

        while (!currentId.equals(start.getId())) {
            Edge edge = previousEdge.get(currentId);

            if (edge == null) {
                return new ArrayList<>();
            }

            route.add(edge);
            currentId = edge.getSource().getId();
        }

        Collections.reverse(route);
        return route;
    }

    private double heuristic(Node a, Node b) {
        return haversine(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude()) * 1000.0;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double x = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2)
                * Math.sin(lonDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));

        return R * c;
    }

    private static class NodeCost {
        String nodeId;
        double fCost;
        double gCost;

        NodeCost(String nodeId, double fCost, double gCost) {
            this.nodeId = nodeId;
            this.fCost = fCost;
            this.gCost = gCost;
        }
    }
}