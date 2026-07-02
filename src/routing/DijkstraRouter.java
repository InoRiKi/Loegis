package src.routing;

import src.model.CrisisGraph;
import src.model.Edge;
import src.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * DijkstraRouter
 * --------------
 * ใช้หาเส้นทางที่มีต้นทุนต่ำที่สุดแบบแน่นอน
 *
 * ต่างจาก ACO:
 *   - ACO = สุ่มเดินหลายรอบ อาจหาไม่เจอถ้าไกลมาก
 *   - Dijkstra = คำนวณเป็นระบบ หาเส้นทางที่ดีที่สุดจากค่า weight ได้แน่นอน
 *
 * ระบบนี้ใช้ edge.getDynamicWeight(alpha, beta)
 * ดังนั้นยังคำนึงถึง:
 *   - ระยะทาง
 *   - ความเสี่ยง
 *   - น้ำท่วมที่ทำให้รถช้าลง
 *   - ถนนที่รถผ่านไม่ได้
 */
public class DijkstraRouter {

    private final CrisisGraph graph;
    private final Map<String, List<Edge>> adjacency;

    public DijkstraRouter(CrisisGraph graph) {
        this.graph = graph;
        this.adjacency = new HashMap<>();
        buildAdjacencyList();
    }

    /**
     * สร้างรายการถนนที่ออกจากแต่ละโหนด
     * เช่น node A มีถนนออกไป B, C, D
     */
    private void buildAdjacencyList() {
        for (Edge edge : graph.getAllEdges().values()) {
            String sourceId = edge.getSource().getId();
            adjacency.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(edge);
        }
    }

    /**
     * หาเส้นทางต้นทุนต่ำที่สุดจาก start ไป destination
     */
    public List<Edge> findShortestRoute(Node start, Node destination, double alpha, double beta) {
        System.out.println("[🧭 Dijkstra] เริ่มหาเส้นทางต้นทุนต่ำที่สุดแบบไม่สุ่ม...");

        if (start == null || destination == null) {
            System.out.println("[⚠️] จุดเริ่มต้นหรือจุดหมายเป็น null");
            return new ArrayList<>();
        }

        Map<String, Double> distance = new HashMap<>();
        Map<String, Edge> previousEdge = new HashMap<>();

        for (String nodeId : graph.getAllNodes().keySet()) {
            distance.put(nodeId, Double.POSITIVE_INFINITY);
        }

        distance.put(start.getId(), 0.0);

        PriorityQueue<NodeCost> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost));
        pq.add(new NodeCost(start.getId(), 0.0));

        while (!pq.isEmpty()) {
            NodeCost current = pq.poll();

            if (current.cost > distance.getOrDefault(current.nodeId, Double.POSITIVE_INFINITY)) {
                continue;
            }

            if (current.nodeId.equals(destination.getId())) {
                break;
            }

            List<Edge> edges = adjacency.getOrDefault(current.nodeId, List.of());

            for (Edge edge : edges) {

                // น้ำลึก / ถนนปิด ไม่ให้ผ่าน
                if (!edge.isPassable()) {
                    continue;
                }

                double weight = edge.getDynamicWeight(alpha, beta);

                if (Double.isInfinite(weight) || Double.isNaN(weight)) {
                    continue;
                }

                String nextId = edge.getTarget().getId();
                double newCost = current.cost + weight;

                if (newCost < distance.getOrDefault(nextId, Double.POSITIVE_INFINITY)) {
                    distance.put(nextId, newCost);
                    previousEdge.put(nextId, edge);
                    pq.add(new NodeCost(nextId, newCost));
                }
            }
        }

        if (!previousEdge.containsKey(destination.getId())) {
            System.out.println("[⚠️ Dijkstra] ไม่พบเส้นทางไปถึงปลายทาง");
            return new ArrayList<>();
        }

        List<Edge> route = reconstructRoute(start, destination, previousEdge);

        System.out.println("[✓ Dijkstra] พบเส้นทางที่ดีที่สุด จำนวนถนน "
                + route.size()
                + " เส้น ต้นทุนรวม = "
                + String.format("%.2f", distance.get(destination.getId())));

        return route;
    }

    /**
     * ย้อนกลับจากปลายทางไปต้นทาง เพื่อสร้าง route
     */
    private List<Edge> reconstructRoute(
            Node start,
            Node destination,
            Map<String, Edge> previousEdge
    ) {
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

    /**
     * เก็บ nodeId กับต้นทุนสะสม
     */
    private static class NodeCost {
        String nodeId;
        double cost;

        NodeCost(String nodeId, double cost) {
            this.nodeId = nodeId;
            this.cost = cost;
        }
    }
}