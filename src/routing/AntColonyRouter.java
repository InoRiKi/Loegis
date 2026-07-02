package src.routing;

import src.model.CrisisGraph;
import src.model.Edge;
import src.model.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AntColonyRouter
 * ---------------
 * เป้าหมายของระบบนี้คือหา "เส้นทางที่เร็วที่สุดและไปถึงได้จริง"
 * สำหรับรถส่งเสบียงเข้าพื้นที่เสี่ยงภัย (เช่น น้ำท่วมหาดใหญ่)
 *
 * แนวคิด:
 *   - alpha  -> น้ำหนักของ "ระยะทาง/เวลาเดินทาง" (ยิ่งสูง ยิ่งให้ความสำคัญกับความเร็ว)
 *   - beta   -> น้ำหนักของ "ความเสี่ยง" (ยิ่งสูง ยิ่งหลีกเลี่ยงถนนเสี่ยง เช่น น้ำท่วม/ถนนพัง)
 *   - edge.getDynamicWeight(alpha, beta) จึงเป็น "ต้นทุนรวม" ของถนนแต่ละเส้น
 *     ทางที่ "เร็วที่สุดและไปถึงจริง" คือทางที่ต้นทุนรวมนี้ต่ำที่สุดตลอดเส้นทาง
 *
 * ส่วน pheromone/heuristic exponent ภายในของ ACO (PHEROMONE_INFLUENCE, HEURISTIC_INFLUENCE)
 * แยกออกจาก alpha/beta ของผู้ใช้ เพื่อไม่ให้ความหมายสองชุดทับกัน
 * และไม่กระทบ method signature เดิมที่ Main.java เรียกใช้อยู่
 */
public class AntColonyRouter {

    // ค่าคงที่ภายในของสมการ ACO มาตรฐาน: score = pheromone^P * heuristic^H
    private static final double PHEROMONE_INFLUENCE = 1.0;
    private static final double HEURISTIC_INFLUENCE = 2.0; // เน้น heuristic (ความเร็ว+ความปลอดภัย) มากกว่า pheromone
    private static final double EVAPORATION_RATE = 0.1;
    private static final double DEFAULT_ALPHA = 0.6;
    private static final double DEFAULT_BETA = 0.4;
    private static final int DEFAULT_ITERATIONS = 500;
    private static final int MAX_STEPS_MULTIPLIER = 20; // กันมดเดินวนไม่จบ (คูณกับจำนวนโหนดทั้งหมด)
    private static final double EPSILON = 1e-6;

    private final CrisisGraph graph;
    private final Map<String, Double> pheromoneMap;
    private final Map<String, List<Edge>> adjacency; // pre-built: nodeId -> ถนนที่ออกจากโหนดนั้น

    public AntColonyRouter(CrisisGraph graph) {
        this.graph = graph;
        this.pheromoneMap = new HashMap<>();
        this.adjacency = new HashMap<>();
        initializePheromones();
        buildAdjacencyList();
    }

    private void initializePheromones() {
        for (String edgeId : graph.getAllEdges().keySet()) {
            pheromoneMap.put(edgeId, 1.0);
        }
    }

    // สร้าง adjacency list ล่วงหน้าครั้งเดียว แทนการวน edge ทั้งหมดทุก step (แก้ปัญหา performance)
    private void buildAdjacencyList() {
        for (Edge edge : graph.getAllEdges().values()) {
            String sourceId = edge.getSource().getId();
            adjacency.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(edge);
        }
    }

    /**
     * ค้นหาเส้นทางส่งเสบียงที่เร็วที่สุดและไปถึงได้จริง ด้วย Ant Colony Optimization
     */
    public List<Edge> findOptimalRoute(Node start, Node destination, double alpha, double beta, int iterations) {
        System.out.println("[🧠 AI] เริ่มต้นค้นหาเส้นทางส่งเสบียงที่เร็วที่สุด ด้วย ACO จำนวน " + iterations + " รอบ...");

        if (start == null || destination == null) {
            System.out.println("[⚠️] จุดเริ่มต้นหรือจุดหมายเป็น null ไม่สามารถคำนวณเส้นทางได้");
            return new ArrayList<>();
        }

        List<Edge> globalBestRoute = new ArrayList<>();
        double globalMinCost = Double.MAX_VALUE;
        int successfulRuns = 0;

        int maxSteps = Math.max(graph.getAllNodes().size() * MAX_STEPS_MULTIPLIER, 50);

        for (int iter = 0; iter < iterations; iter++) {
            AntResult result = runSingleAnt(start, destination, alpha, beta, maxSteps);

            if (result != null) {
                successfulRuns++;
                if (result.cost < globalMinCost) {
                    globalMinCost = result.cost;
                    globalBestRoute = result.route;
                }
                depositPheromone(result.route, result.cost);
            }

            // ฟีโรโมนระเหยทุกรอบ ไม่ว่ารอบนั้นจะสำเร็จหรือไม่
            evaporatePheromones(EVAPORATION_RATE);

            // Elitist update: เติมฟีโรโมนพิเศษให้เส้นทางที่ดีที่สุดเท่าที่เคยเจอ
            // ช่วยให้ระบบลู่เข้าหาคำตอบเร็วขึ้น เหมาะกับสถานการณ์เร่งด่วนที่ iterations อาจมีจำกัด
            if (!globalBestRoute.isEmpty()) {
                depositPheromone(globalBestRoute, globalMinCost);
            }
        }

        if (successfulRuns == 0) {
            System.out.println("[⚠️] ไม่พบเส้นทางที่ไปถึงปลายทางได้เลยในทุกรอบที่รัน (" + iterations + " รอบ) "
                    + "อาจเป็นเพราะถนนถูกตัดขาดทั้งหมดจากภัยพิบัติ");
        } else {
            System.out.println("[✓] ACO ประมวลผลเสร็จสิ้น! สำเร็จ " + successfulRuns + "/" + iterations
                    + " รอบ พบเส้นทางส่งเสบียงที่เร็วที่สุด ประกอบด้วยถนน "
                    + globalBestRoute.size() + " เส้น (ต้นทุนรวม = " + String.format("%.2f", globalMinCost) + ")");
        }

        return globalBestRoute;
    }

    /** ผลลัพธ์ของมด 1 ตัวที่เดินสำเร็จถึงปลายทาง */
    private static class AntResult {
        final List<Edge> route;
        final double cost;

        AntResult(List<Edge> route, double cost) {
            this.route = route;
            this.cost = cost;
        }
    }

    // จำลองมด 1 ตัวเดินทางจาก start ไป destination ตามกลไก ACO
    private AntResult runSingleAnt(Node start, Node destination, double alpha, double beta, int maxSteps) {
        List<Edge> currentRoute = new ArrayList<>();
        Node currentNode = start;

        Set<String> visitedNodes = new HashSet<>();
        visitedNodes.add(currentNode.getId());

        int steps = 0;

        while (currentNode != null && !currentNode.getId().equals(destination.getId()) && steps < maxSteps) {

            List<Edge> availableEdges = adjacency.getOrDefault(currentNode.getId(), List.of()).stream()
                    .filter(e -> e.isPassable())
                    .toList();

            if (availableEdges.isEmpty()) {
                return null; // เจอทางตัน มดตัวนี้ไปไม่ถึงปลายทาง
            }

            Edge selectedEdge = selectEdgeProbabilistically(availableEdges, alpha, beta);
            if (selectedEdge == null) {
                return null;
            }

            currentRoute.add(selectedEdge);
            currentNode = selectedEdge.getTarget();
            visitedNodes.add(currentNode.getId());
            steps++;
        }

        if (currentNode == null || !currentNode.getId().equals(destination.getId())) {
            return null; // หมดจำนวนก้าวที่อนุญาตก่อนถึงปลายทาง
        }

        double cost = FitnessEvaluator.evaluateRouteCost(currentRoute, alpha, beta);
        return new AntResult(currentRoute, Math.max(cost, EPSILON));
    }

    /**
     * เลือกถนนแบบสุ่มถ่วงน้ำหนัก (roulette-wheel selection) ตามสมการ ACO มาตรฐาน:
     *   score(edge) = pheromone^PHEROMONE_INFLUENCE * heuristic^HEURISTIC_INFLUENCE
     * แทนการเลือกแบบ greedy เดิม เพื่อให้มดแต่ละตัว/แต่ละรอบมีโอกาส explore เส้นทางต่างกัน
     */
    private Edge selectEdgeProbabilistically(List<Edge> availableEdges, double alpha, double beta) {
        Map<Edge, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Edge edge : availableEdges) {
            double pheromone = pheromoneMap.getOrDefault(edge.getId(), 1.0);
            double dynamicWeight = edge.getDynamicWeight(alpha, beta);


            double safety = edge.getSafetyScore();
            if (safety <= 0) continue;
            // heuristic: ยิ่งต้นทุน (เวลา+ความเสี่ยง) ต่ำ ยิ่งน่าดึงดูด
            double heuristic = safety / (dynamicWeight + EPSILON);

            double score = Math.pow(pheromone, PHEROMONE_INFLUENCE) * Math.pow(heuristic, HEURISTIC_INFLUENCE);

            // กันกรณี score เพี้ยน (เช่น NaN จาก dynamicWeight ติดลบผิดปกติ)
            if (score > 0 && !Double.isNaN(score) && !Double.isInfinite(score)) {
                scores.put(edge, score);
                totalScore += score;
            }
        }

        if (scores.isEmpty() || totalScore <= 0) {
            // fallback: ถ้าคำนวณคะแนนไม่ได้เลย ให้สุ่มเท่าๆกันแทนที่จะค้างหรือ return null
            return availableEdges.get((int) (Math.random() * availableEdges.size()));
        }

        double r = Math.random() * totalScore;
        double cumulative = 0.0;
        for (Map.Entry<Edge, Double> entry : scores.entrySet()) {
            cumulative += entry.getValue();
            if (cumulative >= r) {
                return entry.getKey();
            }
        }

        // ป้องกัน floating point edge case ที่ cumulative ไม่ทัน r เพราะ rounding
        return availableEdges.get(availableEdges.size() - 1);
    }

    // เพิ่มฟีโรโมนบนถนนที่มดเดินผ่านสำเร็จ ยิ่งต้นทุนรวมต่ำ ยิ่งได้ฟีโรโมนมาก
    private void depositPheromone(List<Edge> route, double cost) {
        double safeCost = Math.max(cost, EPSILON);
        double deposit = 100.0 / safeCost;

        for (Edge edge : route) {
            double currentPheromone = pheromoneMap.getOrDefault(edge.getId(), 1.0);
            pheromoneMap.put(edge.getId(), currentPheromone + deposit);
        }
    }

    private void evaporatePheromones(double rate) {
        for (String edgeId : pheromoneMap.keySet()) {
            double current = pheromoneMap.get(edgeId);
            pheromoneMap.put(edgeId, current * (1.0 - rate));
        }
    }
}