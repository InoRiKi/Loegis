package src.server;

import src.io.GraphImporter;
import src.model.CrisisGraph;
import src.model.Node;
import src.model.Edge;
import src.simulation.DisasterSimulator;
import src.simulation.FleetManager;
import src.routing.AntColonyRouter;
import src.routing.FitnessEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SimulationState
 * ----------------
 * เวอร์ชัน server-side ของตัวกลางเรียก logic เดิม (model / io / routing / simulation)
 * ไม่แก้ logic เดิมเลยแม้แต่บรรทัดเดียว — ทุกเมธอดในนี้แค่เรียกคลาสเดิมแล้วเก็บ state
 * เพื่อให้ ApiServer แปลงเป็น JSON ส่งให้หน้าเว็บ (Leaflet) ไปวาดต่อ
 *
 * ต่างจาก SimulationController เดิม (ที่ใช้กับ Swing) ตรงที่:
 *   - กำหนด depot/camp ได้ "ตามที่ผู้ใช้คลิกเลือกจริงบนแผนที่" ผ่าน setDepotById/setCampById
 *     แทนการ auto-pick โหนดแรก/โหนดสุดท้ายแบบเดิม (แก้ปัญหา "กดกำหนดแคมไม่ได้")
 *   - มี toGraphJson()/toRouteJson() แปลงข้อมูลกราฟเป็น Map/List ธรรมดา พร้อมส่งผ่าน Json.stringify()
 */
public class SimulationState {

    private final Consumer<String> logCallback;

    private CrisisGraph graph;
    private Node depot;
    private Node refugeeCamp;
    private FleetManager fleet;
    private List<Edge> optimalRoute;
    private double mockRouteCost;
    private boolean floodSimulated = false;

    // พารามิเตอร์ (ค่าเริ่มต้นตรงกับของเดิมใน Main.java)
    private double demandWaterPacks = 500.0;
    private double demandMedicalKits = 50.0;
    private double timeWindowStartMin = 60.0;
    private double timeWindowEndMin = 180.0;

    public SimulationState(Consumer<String> logCallback) {
        this.logCallback = logCallback;
    }

    private void log(String msg) {
        if (logCallback != null) logCallback.accept(msg);
    }

    public boolean isGraphLoaded() {
        return graph != null && !graph.getAllNodes().isEmpty();
    }

    public CrisisGraph getGraph() { return graph; }
    public Node getDepot() { return depot; }
    public Node getRefugeeCamp() { return refugeeCamp; }
    public List<Edge> getOptimalRoute() { return optimalRoute; }

    // ==================================================================
    //  โหลดแผนที่
    // ==================================================================

    public boolean loadMap(String mapFilePath) {
        log("[+] กำลังเปิดอ่านไฟล์แผนที่จาก: " + mapFilePath);
        graph = GraphImporter.importMap(mapFilePath);

        // รีเซ็ต state ทุกตัวที่ผูกกับกราฟเก่า เพื่อกันบัคเวลาโหลดไฟล์ใหม่ทับไฟล์เดิม
        depot = null;
        refugeeCamp = null;
        fleet = null;
        optimalRoute = null;
        floodSimulated = false;

        if (graph.getAllNodes().isEmpty()) {
            log("[❌ System Halt] ไม่สามารถดำเนินการต่อได้เนื่องจากไม่มีข้อมูลกราฟ");
            return false;
        }

        log("[🎉 Success] โหลดกราฟสำเร็จ — จำนวนโหนด: " + graph.getAllNodes().size()
                + ", จำนวนถนน: " + graph.getAllEdges().size());
        return true;
    }

    // ==================================================================
    //  กำหนดคลังเสบียง (depot) / ค่ายผู้ประสบภัย (camp) — เลือกได้ตรงๆ จาก nodeId ที่คลิกบนแผนที่
    // ==================================================================

    public boolean setDepotById(String nodeId) {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }
        Node node = graph.getNode(nodeId);
        if (node == null) {
            log("[⚠️] ไม่พบโหนด id: " + nodeId);
            return false;
        }
        // ถ้าเคยตั้ง depot จุดอื่นไว้ก่อน ให้ยกเลิกสถานะ depot เดิมออกก่อน กันมีหลาย depot ค้างอยู่
        if (depot != null) {
            depot.setDepot(false);
        }
        depot = node;
        depot.setDepot(true);
        log("[📍 Setup] กำหนดคลังเสบียงที่โหนด: " + depot.getId());
        return true;
    }

    public boolean setCampById(String nodeId) {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }
        Node node = graph.getNode(nodeId);
        if (node == null) {
            log("[⚠️] ไม่พบโหนด id: " + nodeId);
            return false;
        }
        // ล้าง demand ของแคมเก่า (ถ้ามี) ก่อนย้ายไปแคมใหม่ ไม่ให้ demand ค้างอยู่หลายจุด
        if (refugeeCamp != null) {
            refugeeCamp.getDemands().clear();
        }
        refugeeCamp = node;
        refugeeCamp.addDemand("Water", demandWaterPacks);
        refugeeCamp.addDemand("Medical_Kit", demandMedicalKits);
        refugeeCamp.setTimeWindow(timeWindowStartMin, timeWindowEndMin);
        log("[📍 Setup] กำหนดค่ายประสบภัยที่โหนด: " + refugeeCamp.getId()
                + " (ต้องการน้ำ " + (int) demandWaterPacks + " แพ็ค, เวชภัณฑ์ " + (int) demandMedicalKits + " ชุด)");
        return true;
    }

    public void setDemandParams(double water, double medical, double startMin, double endMin) {
        this.demandWaterPacks = water;
        this.demandMedicalKits = medical;
        this.timeWindowStartMin = startMin;
        this.timeWindowEndMin = endMin;
        // ถ้ามีแคมตั้งอยู่แล้ว ให้อัปเดต demand ของแคมนั้นทันทีตามพารามิเตอร์ใหม่
        if (refugeeCamp != null) {
            refugeeCamp.getDemands().clear();
            refugeeCamp.addDemand("Water", water);
            refugeeCamp.addDemand("Medical_Kit", medical);
            refugeeCamp.setTimeWindow(startMin, endMin);
        }
    }

    // ==================================================================
    //  จำลองน้ำท่วม
    // ==================================================================

    public boolean simulateFlood(double lat, double lon, double radiusKm) {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }

        // รีเซ็ตค่าความเสี่ยง/เวลาก่อนจำลองรอบใหม่ กันน้ำท่วมสองจุดทับกันแบบสะสมผิดสมการ
        graph.resetGraphEnvironment();

        log("[⚠️] กำลังจำลองสภาวะน้ำท่วม ณ พิกัด (" + lat + ", " + lon + ") รัศมี " + radiusKm + " กม.");
        DisasterSimulator.simulateFloodZone(graph, lat, lon, radiusKm);
        floodSimulated = true;

        long affected = graph.getAllEdges().values().stream().filter(e -> e.getRiskLevel() > 0.0).count();
        log("[✓] มีเส้นทางถนนได้รับผลกระทบจากน้ำท่วมขังทั้งหมด: " + affected + " เส้นทาง");
        return true;
    }

    // ==================================================================
    //  จัดเตรียมกองรถกู้ภัย
    // ==================================================================

    public boolean setupFleet(int fleetSize, double vehicleCapacityKg) {
        if (depot == null) {
            log("[⚠️] กรุณากำหนดคลังเสบียงก่อน");
            return false;
        }
        fleet = new FleetManager();
        for (int i = 1; i <= fleetSize; i++) {
            String vehicleId = String.format("Rescue_Truck_%02d", i);
            fleet.addVehicle(vehicleId, vehicleCapacityKg, depot);
        }
        log("[🚚 Fleet] สแตนบายรถกู้ภัยจำนวน " + fleet.getAllVehicles().size() + " คัน ณ คลังเสบียง");
        return true;
    }

    // ==================================================================
    //  หาเส้นทางที่ดีที่สุดด้วย Ant Colony Optimization
    // ==================================================================

    public boolean runAco(double alpha, double beta, int iterations) {
        if (depot == null || refugeeCamp == null) {
            log("[⚠️] กรุณากำหนดคลังเสบียงและค่ายประสบภัยก่อน");
            return false;
        }

        log("🤖 เริ่มต้นเฟสการคำนวณและประเมินผลเส้นทาง (Optimization Phase)");
        AntColonyRouter acoRouter = new AntColonyRouter(graph);
        optimalRoute = acoRouter.findOptimalRoute(depot, refugeeCamp, alpha, beta, iterations);

        if (optimalRoute == null || optimalRoute.isEmpty()) {
            log("[⚠️] ไม่พบเส้นทางที่ไปถึงปลายทางได้ — ลองลดรัศมีน้ำท่วม หรือเพิ่มจำนวนรอบ iterations");
            return false;
        }

        double totalDistance = optimalRoute.stream().mapToDouble(Edge::getDistance).sum();
        mockRouteCost = FitnessEvaluator.evaluateRouteCost(optimalRoute, alpha, beta);
        log("[✓] พบเส้นทางที่ดีที่สุด ประกอบด้วยถนน " + optimalRoute.size()
                + " เส้น (ระยะทางรวม ~" + String.format("%.0f", totalDistance)
                + " เมตร, ต้นทุนรวม = " + String.format("%.2f", mockRouteCost) + ")");
        return true;
    }

    // ==================================================================
    //  แปลงเป็น JSON-friendly object สำหรับส่งให้ frontend
    // ==================================================================

    /** ข้อมูลกราฟทั้งหมด (โหนด + ถนน) แปลงเป็น Map/List ธรรมดา พร้อม stringify */
    public Map<String, Object> toStateJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("loaded", isGraphLoaded());

        if (!isGraphLoaded()) {
            return root;
        }

        List<Object> nodesJson = new ArrayList<>();
        for (Node n : graph.getAllNodes().values()) {
            Map<String, Object> nj = new LinkedHashMap<>();
            nj.put("id", n.getId());
            nj.put("lat", n.getLatitude());
            nj.put("lon", n.getLongitude());
            nj.put("isDepot", n.isDepot());
            nj.put("hasDemand", !n.getDemands().isEmpty());
            nodesJson.add(nj);
        }

        List<Object> edgesJson = new ArrayList<>();
        for (Edge e : graph.getAllEdges().values()) {
            Map<String, Object> ej = new LinkedHashMap<>();
            ej.put("id", e.getId());
            ej.put("sourceId", e.getSource().getId());
            ej.put("targetId", e.getTarget().getId());
            ej.put("distance", e.getDistance());
            ej.put("riskLevel", e.getRiskLevel());
            edgesJson.add(ej);
        }

        root.put("nodes", nodesJson);
        root.put("edges", edgesJson);
        root.put("depotId", depot != null ? depot.getId() : null);
        root.put("campId", refugeeCamp != null ? refugeeCamp.getId() : null);
        root.put("floodSimulated", floodSimulated);
        root.put("fleetSize", fleet != null ? fleet.getAllVehicles().size() : 0);
        root.put("routeEdgeIds", optimalRoute != null
                ? optimalRoute.stream().map(Edge::getId).toList()
                : List.of());
        root.put("mockRouteCost", mockRouteCost);

        long depotCount = graph.getAllNodes().values().stream().filter(Node::isDepot).count();
        long demandCount = graph.getAllNodes().values().stream().filter(n -> !n.getDemands().isEmpty()).count();
        root.put("depotCount", depotCount);
        root.put("demandCount", demandCount);

        return root;
    }
}
