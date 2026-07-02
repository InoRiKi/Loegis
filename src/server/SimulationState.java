package src.server;

import src.io.GraphImporter;
import src.model.CrisisGraph;
import src.model.Node;
import src.model.Edge;
import src.simulation.DisasterSimulator;
import src.simulation.FleetManager;
import src.simulation.FloodZone;
import src.simulation.DisasterSimulator.FloodLevel;
import src.routing.AntColonyRouter;
import src.routing.DijkstraRouter;
import src.routing.FitnessEvaluator;
import src.routing.AStarRouter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SimulationState
 * ----------------
 * คลาสนี้เป็นตัวกลางฝั่ง Server สำหรับเก็บสถานะทั้งหมดของการจำลอง
 *
 * หน้าที่หลัก:
 *   1. โหลดแผนที่เข้าสู่ CrisisGraph
 *   2. เก็บตำแหน่ง Depot และ Camp
 *   3. เก็บจุดน้ำท่วมหลายจุด
 *   4. จำลองน้ำท่วมหลายระดับ เช่น ตื้น / กลาง / ลึก
 *   5. ส่งข้อมูลสถานะกลับไปให้หน้าเว็บวาดบนแผนที่
 *
 * จุดที่เพิ่มใหม่:
 *   - floodZones เก็บจุดน้ำท่วมหลายจุด
 *   - addFloodZone() เพิ่มจุดน้ำท่วมจากการคลิกบนแผนที่
 *   - clearFloodZones() ล้างจุดน้ำท่วมทั้งหมด
 *   - toStateJson() ส่งข้อมูล floodZones และ blockedEdges กลับไปหน้าเว็บ
 */
public class SimulationState {

    private final Consumer<String> logCallback;
    private final List<Node> rescuePoints = new ArrayList<>();

    private CrisisGraph graph;
    private Node depot;
    private Node refugeeCamp;
    private FleetManager fleet;
    private List<Edge> optimalRoute;
    private double mockRouteCost;
    private boolean floodSimulated = false;

    // เก็บจุดน้ำท่วมหลายจุดที่ผู้ใช้คลิกบนแผนที่
    private final List<FloodZone> floodZones = new ArrayList<>();

    // พารามิเตอร์ค่ายผู้ประสบภัย
    private double demandWaterPacks = 500.0;
    private double demandMedicalKits = 50.0;
    private double timeWindowStartMin = 60.0;
    private double timeWindowEndMin = 180.0;

    public SimulationState(Consumer<String> logCallback) {
        this.logCallback = logCallback;
    }

    private void log(String msg) {
        if (logCallback != null) {
            logCallback.accept(msg);
        }
    }

    public boolean addRescuePointById(String nodeId) {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }

        Node node = graph.getNode(nodeId);

        if (node == null) {
            log("[⚠️] ไม่พบโหนดผู้ประสบภัย id: " + nodeId);
            return false;
        }

        if (!rescuePoints.contains(node)) {
            rescuePoints.add(node);
        }

        optimalRoute = null;

        log("[🆘 Rescue] เพิ่มจุดผู้ประสบภัยที่โหนด: " + node.getId());

        return true;
    }

    public boolean isGraphLoaded() {
        return graph != null && !graph.getAllNodes().isEmpty();
    }

    public CrisisGraph getGraph() {
        return graph;
    }

    public Node getDepot() {
        return depot;
    }

    public Node getRefugeeCamp() {
        return refugeeCamp;
    }

    public List<Edge> getOptimalRoute() {
        return optimalRoute;
    }

    // ==================================================================
    //  STEP 1: โหลดแผนที่
    // ==================================================================

    /**
     * โหลดไฟล์ GraphML เข้าสู่ CrisisGraph
     *
     * เมื่อโหลดแผนที่ใหม่ จะล้าง state เดิมทั้งหมด เช่น
     *   - depot
     *   - refugeeCamp
     *   - fleet
     *   - route
     *   - floodZones
     */
    public boolean loadMap(String mapFilePath, boolean respectOneWay) {
        log("[+] กำลังเปิดอ่านไฟล์แผนที่จาก: " + mapFilePath);
        log("[🚦] โหมดการจราจร: " + (respectOneWay ? "เคารพ One-way" : "Emergency Mode / ย้อนศรได้"));

        graph = GraphImporter.importMap(mapFilePath, respectOneWay);

        depot = null;
        refugeeCamp = null;
        fleet = null;
        optimalRoute = null;
        floodSimulated = false;
        floodZones.clear();

        if (graph.getAllNodes().isEmpty()) {
            log("[❌ System Halt] ไม่สามารถดำเนินการต่อได้เนื่องจากไม่มีข้อมูลกราฟ");
            return false;
        }

        rescuePoints.clear();

        log("[🎉 Success] โหลดกราฟสำเร็จ — จำนวนโหนด: "
                + graph.getAllNodes().size()
                + ", จำนวนถนน: "
                + graph.getAllEdges().size());

        return true;
    }

    public boolean loadMap(String mapFilePath) {
        return loadMap(mapFilePath, true);
    }

    // ==================================================================
    //  STEP 2: กำหนด Depot / Camp
    // ==================================================================

    /**
     * กำหนดคลังเสบียงจาก nodeId
     */
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

        if (depot != null) {
            depot.setDepot(false);
        }

        depot = node;
        depot.setDepot(true);

        log("[📍 Setup] กำหนดคลังเสบียงที่โหนด: " + depot.getId());

        return true;
    }

    /**
     * กำหนดค่ายผู้ประสบภัยจาก nodeId
     */
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

        if (refugeeCamp != null) {
            refugeeCamp.getDemands().clear();
        }

        refugeeCamp = node;
        refugeeCamp.addDemand("Water", demandWaterPacks);
        refugeeCamp.addDemand("Medical_Kit", demandMedicalKits);
        refugeeCamp.setTimeWindow(timeWindowStartMin, timeWindowEndMin);

        log("[📍 Setup] กำหนดค่ายประสบภัยที่โหนด: "
                + refugeeCamp.getId()
                + " (น้ำ "
                + (int) demandWaterPacks
                + " แพ็ค, เวชภัณฑ์ "
                + (int) demandMedicalKits
                + " ชุด)");

        return true;
    }

    /**
     * ตั้งค่าความต้องการของ Camp
     */
    public void setDemandParams(double water, double medical, double startMin, double endMin) {
        this.demandWaterPacks = water;
        this.demandMedicalKits = medical;
        this.timeWindowStartMin = startMin;
        this.timeWindowEndMin = endMin;

        if (refugeeCamp != null) {
            refugeeCamp.getDemands().clear();
            refugeeCamp.addDemand("Water", water);
            refugeeCamp.addDemand("Medical_Kit", medical);
            refugeeCamp.setTimeWindow(startMin, endMin);
        }
    }

    // ==================================================================
    //  STEP 3: ระบบน้ำท่วมหลายจุด
    // ==================================================================

    /**
     * เพิ่มจุดน้ำท่วม 1 จุด
     *
     * ใช้เมื่อผู้ใช้คลิกบนแผนที่ แล้วเลือก:
     *   - รัศมีน้ำท่วม
     *   - ระดับน้ำ SHALLOW / MEDIUM / DEEP
     *
     * หลังเพิ่มจุดแล้ว ระบบจะจำลองน้ำท่วมใหม่ทั้งหมดจาก floodZones ทุกจุด
     */
    public boolean addFloodZone(double lat, double lon, double radiusKm, String levelText) {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }

        FloodLevel level;

        try {
            level = FloodLevel.valueOf(levelText.toUpperCase());
        } catch (Exception e) {
            level = FloodLevel.MEDIUM;
        }

        FloodZone zone = new FloodZone(lat, lon, radiusKm, level);
        floodZones.add(zone);

        log("[🌊] เพิ่มจุดน้ำท่วมใหม่: พิกัด ("
                + lat
                + ", "
                + lon
                + "), รัศมี "
                + radiusKm
                + " กม., ระดับ "
                + level);

        DisasterSimulator.simulateFloodZones(graph, floodZones);

        floodSimulated = true;
        optimalRoute = null;

        return true;
    }

    /**
     * ล้างจุดน้ำท่วมทั้งหมด
     *
     * ใช้เมื่อต้องการเริ่มกำหนดน้ำท่วมใหม่
     * ถนนทุกเส้นจะกลับมาเป็นปกติ
     */
    public boolean clearFloodZones() {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }

        floodZones.clear();
        graph.resetGraphEnvironment();

        floodSimulated = false;
        optimalRoute = null;

        log("[🔄] ล้างจุดน้ำท่วมทั้งหมดแล้ว ถนนกลับสู่สถานะปกติ");

        return true;
    }

    /**
     * เมธอดเดิมสำหรับจำลองน้ำท่วม 1 จุด
     *
     * เก็บไว้เพื่อไม่ให้ API เดิมพัง
     * ค่าเริ่มต้นจะใช้ระดับ MEDIUM
     */
    public boolean simulateFlood(double lat, double lon, double radiusKm) {
        return addFloodZone(lat, lon, radiusKm, "MEDIUM");
    }

    // ==================================================================
    //  STEP 4: จัดเตรียมกองรถ
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

        log("[🚚 Fleet] สแตนบายรถกู้ภัยจำนวน "
                + fleet.getAllVehicles().size()
                + " คัน ณ คลังเสบียง");

        return true;
    }

    // ==================================================================
    //  STEP 5: หาเส้นทางด้วย ACO
    // ==================================================================

    public boolean runAco(double alpha, double beta, int iterations) {
        if (depot == null) {
            log("[⚠️] กรุณากำหนดคลังเสบียง/ฐานกู้ภัยก่อน");
            return false;
        }

        if (rescuePoints == null || rescuePoints.isEmpty()) {
            log("[⚠️] กรุณาปักจุดผู้ประสบภัยก่อน แล้วค่อยใช้ ACO");
            return false;
        }

        log("[🐜 ACO Rescue] เริ่มคำนวณเส้นทางช่วยเหลือผู้ประสบภัย");

        AntColonyRouter router = new AntColonyRouter(graph);

        List<Edge> fullRoute = new ArrayList<>();
        Node current = depot;

        for (Node rescuePoint : rescuePoints) {
            List<Edge> segment = router.findOptimalRoute(
                    current,
                    rescuePoint,
                    alpha,
                    beta,
                    iterations
            );

            if (segment == null || segment.isEmpty()) {
                log("[⚠️] ACO หาเส้นทางไปยังผู้ประสบภัยโหนด "
                        + rescuePoint.getId()
                        + " ไม่สำเร็จ");
                continue;
            }

            fullRoute.addAll(segment);
            current = rescuePoint;

            log("[🆘] เพิ่มเส้นทางช่วยเหลือไปยังผู้ประสบภัย: "
                    + rescuePoint.getId());
        }

        if (fullRoute.isEmpty()) {
            log("[❌] ไม่พบเส้นทางช่วยเหลือผู้ประสบภัยด้วย ACO");
            return false;
        }

        optimalRoute = fullRoute;
        mockRouteCost = FitnessEvaluator.evaluateRouteCost(optimalRoute, alpha, beta);

        long truckEdges = optimalRoute.stream()
                .filter(e -> e.getRiskLevel() < 0.45)
                .count();

        long boatEdges = optimalRoute.stream()
                .filter(e -> e.getRiskLevel() >= 0.45)
                .count();

        log("[✓ ACO Rescue] คำนวณเส้นทางช่วยเหลือสำเร็จ");
        log("[🚚/🚤] ใช้รถ " + truckEdges + " ช่วง, ใช้เรือ " + boatEdges + " ช่วง");
        log("[📊] ต้นทุนรวม = " + String.format("%.2f", mockRouteCost));

        return true;
    }
    // ==================================================================
    //  ส่งข้อมูลกลับไปหน้าเว็บ
    // ==================================================================

    /**
     * แปลงสถานะทั้งหมดเป็น JSON-friendly Map
     *
     * หน้าเว็บจะเอาข้อมูลนี้ไปวาด:
     *   - nodes
     *   - edges
     *   - depot
     *   - camp
     *   - route
     *   - floodZones
     *   - blockedEdges
     */

    public boolean setTrafficMode(boolean respectOneWay) {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน");
            return false;
        }

        graph.setRespectOneWay(respectOneWay);
        optimalRoute = null;

        long truckEdges = optimalRoute.stream()
                .filter(e -> e.getRescueVehicleType().equals("TRUCK"))
                .count();

        long boatEdges = optimalRoute.stream()
                .filter(e -> e.getRescueVehicleType().equals("BOAT"))
                .count();

        log("[🚑 Rescue Mode] เส้นทางนี้ใช้รถ "
                + truckEdges
                + " ช่วง และใช้เรือ "
                + boatEdges
                + " ช่วง");

        log(respectOneWay
                ? "[🚦] เปลี่ยนเป็นโหมดจราจรจริง — เคารพ One-way"
                : "[🚨] เปลี่ยนเป็น Emergency Mode — รถกู้ภัยย้อนศรได้");

        return true;
    }

    public boolean runRescueMission() {
        if (depot == null) {
            log("[⚠️] กรุณากำหนดฐานกู้ภัย/คลังเสบียงก่อน");
            return false;
        }

        if (rescuePoints == null || rescuePoints.isEmpty()) {
            log("[⚠️] กรุณาปักจุดผู้ประสบภัยก่อน");
            return false;
        }

        log("[🚨 Rescue] เริ่มวางแผนช่วยเหลือผู้ประสบภัย");
        log("[🆘] จำนวนผู้ประสบภัยทั้งหมด: " + rescuePoints.size() + " จุด");

        double alpha = 0.6;
        double beta = 0.4;

        DijkstraRouter pathFinder = new DijkstraRouter(graph);

        List<Edge> fullRoute = new ArrayList<>();
        Node current = depot;

        for (Node rescuePoint : rescuePoints) {
            List<Edge> segment = pathFinder.findShortestRoute(
                    current,
                    rescuePoint,
                    alpha,
                    beta
            );

            if (segment == null || segment.isEmpty()) {
                log("[⚠️] หาเส้นทางไปยังผู้ประสบภัย "
                        + rescuePoint.getId()
                        + " ไม่สำเร็จ");
                continue;
            }

            fullRoute.addAll(segment);

            log("[🆘] ช่วยเหลือผู้ประสบภัยที่โหนด: "
                    + rescuePoint.getId());

            current = rescuePoint;
        }

        if (fullRoute.isEmpty()) {
            log("[❌] ไม่พบเส้นทางช่วยเหลือผู้ประสบภัย");
            return false;
        }

        optimalRoute = fullRoute;
        mockRouteCost = FitnessEvaluator.evaluateRouteCost(optimalRoute, alpha, beta);

        long truckParts = optimalRoute.stream()
                .filter(e -> e.getRiskLevel() < 0.45)
                .count();

        long boatParts = optimalRoute.stream()
                .filter(e -> e.getRiskLevel() >= 0.45)
                .count();

        log("[✓ Rescue] วางแผนช่วยเหลือสำเร็จ");
        log("[🚚] ใช้รถในช่วงถนนปกติ/น้ำตื้น: " + truckParts + " ช่วง");
        log("[🚤] ใช้เรือในช่วงน้ำกลาง/น้ำลึก: " + boatParts + " ช่วง");
        log("[📊] ต้นทุนรวม = " + String.format("%.2f", mockRouteCost));

        return true;
    }

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
            ej.put("passable", e.isPassable());
            ej.put("trafficAllowed", e.isTrafficAllowed());
            ej.put("reverseEdge", e.isReverseEdge());
            ej.put("roadPassable", e.isRoadPassable());
            edgesJson.add(ej);
        }

        List<Object> floodZonesJson = new ArrayList<>();

        for (FloodZone zone : floodZones) {
            Map<String, Object> zj = new LinkedHashMap<>();
            zj.put("lat", zone.getLatitude());
            zj.put("lon", zone.getLongitude());
            zj.put("radiusKm", zone.getRadiusKm());
            zj.put("level", zone.getLevel().toString());
            floodZonesJson.add(zj);
        }

        long blockedEdges = graph.getAllEdges().values().stream()
                .filter(e -> !e.isPassable())
                .count();

        long depotCount = graph.getAllNodes().values().stream()
                .filter(Node::isDepot)
                .count();

        long demandCount = graph.getAllNodes().values().stream()
                .filter(n -> !n.getDemands().isEmpty())
                .count();

        root.put("nodes", nodesJson);
        root.put("edges", edgesJson);
        root.put("depotId", depot != null ? depot.getId() : null);
        root.put("campId", refugeeCamp != null ? refugeeCamp.getId() : null);
        root.put("floodSimulated", floodSimulated);
        root.put("floodZones", floodZonesJson);
        root.put("blockedEdges", blockedEdges);
        root.put("fleetSize", fleet != null ? fleet.getAllVehicles().size() : 0);
        root.put("routeEdgeIds", optimalRoute != null
                ? optimalRoute.stream().map(Edge::getId).toList()
                : List.of());
        root.put("mockRouteCost", mockRouteCost);
        root.put("depotCount", depotCount);
        root.put("demandCount", demandCount);
        root.put("rescuePointIds",
                rescuePoints.stream().map(Node::getId).toList());

        return root;
    }
}