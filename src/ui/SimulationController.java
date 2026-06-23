package src.ui;

import src.io.GraphImporter;
import src.model.CrisisGraph;
import src.model.Node;
import src.model.Edge;
import src.simulation.DisasterSimulator;
import src.simulation.FleetManager;
import src.routing.AntColonyRouter;
import src.routing.FitnessEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SimulationController
 * ---------------------
 * ตัวกลางระหว่าง GUI (Swing) กับ logic เดิมของระบบ (model / io / routing / simulation)
 *
 * จุดประสงค์: เดิมทุกอย่างถูกรันรวดเดียวใน Main.main() แบบ static ทำให้ GUI
 * ไม่สามารถ "หยุดดูผลทีละสเตป" ได้ คลาสนี้จึงแยกแต่ละสเตปออกมาเป็นเมธอดที่เรียกทีละครั้งได้
 * โดย "ไม่แก้ logic เดิมเลย" — แค่ห่อ (wrap) การเรียกใช้คลาสเดิมให้เก็บ state ไว้ใช้ข้ามสเตป
 *
 * ทุกเมธอดในนี้จะถูกเรียกจาก background thread (SwingWorker) ไม่ใช่ Event Dispatch Thread
 * ดังนั้นห้าม touch Swing component ตรงนี้ — ให้ใช้ logCallback ส่งข้อความกลับไปแทน
 */
public class SimulationController {

    // ใช้ Consumer<String> เป็นช่องทางส่ง log กลับไปแสดงบน GUI แบบเรียลไทม์ทีละบรรทัด
    private final Consumer<String> logCallback;

    private CrisisGraph graph;
    private Node depot;
    private Node refugeeCamp;
    private FleetManager fleet;
    private List<Edge> optimalRoute;
    private double mockRouteCost;

    // --- พารามิเตอร์ที่ผู้ใช้ปรับได้จาก GUI (ค่าเริ่มต้นตรงกับของเดิมใน Main.java) ---
    private String mapFilePath = "hatyai_map.graphml";

    private double floodLatitude = 7.0100;
    private double floodLongitude = 100.4700;
    private double floodRadiusKm = 2.5;

    private double demandWaterPacks = 500.0;
    private double demandMedicalKits = 50.0;
    private double timeWindowStartMin = 60.0;
    private double timeWindowEndMin = 180.0;

    private int fleetSize = 2;
    private double vehicleCapacityKg = 1000.0;

    private double acoAlpha = 0.6;
    private double acoBeta = 0.4;
    private int acoIterations = 100;

    public SimulationController(Consumer<String> logCallback) {
        this.logCallback = logCallback;
    }

    private void log(String msg) {
        if (logCallback != null) {
            logCallback.accept(msg);
        }
    }

    // ==================================================================
    //  GETTERS/SETTERS พารามิเตอร์ (ผูกกับฟอร์มควบคุมบน GUI)
    // ==================================================================

    public String getMapFilePath() { return mapFilePath; }
    public void setMapFilePath(String mapFilePath) { this.mapFilePath = mapFilePath; }

    public double getFloodLatitude() { return floodLatitude; }
    public void setFloodLatitude(double v) { this.floodLatitude = v; }

    public double getFloodLongitude() { return floodLongitude; }
    public void setFloodLongitude(double v) { this.floodLongitude = v; }

    public double getFloodRadiusKm() { return floodRadiusKm; }
    public void setFloodRadiusKm(double v) { this.floodRadiusKm = v; }

    public double getDemandWaterPacks() { return demandWaterPacks; }
    public void setDemandWaterPacks(double v) { this.demandWaterPacks = v; }

    public double getDemandMedicalKits() { return demandMedicalKits; }
    public void setDemandMedicalKits(double v) { this.demandMedicalKits = v; }

    public double getTimeWindowStartMin() { return timeWindowStartMin; }
    public void setTimeWindowStartMin(double v) { this.timeWindowStartMin = v; }

    public double getTimeWindowEndMin() { return timeWindowEndMin; }
    public void setTimeWindowEndMin(double v) { this.timeWindowEndMin = v; }

    public int getFleetSize() { return fleetSize; }
    public void setFleetSize(int v) { this.fleetSize = v; }

    public double getVehicleCapacityKg() { return vehicleCapacityKg; }
    public void setVehicleCapacityKg(double v) { this.vehicleCapacityKg = v; }

    public double getAcoAlpha() { return acoAlpha; }
    public void setAcoAlpha(double v) { this.acoAlpha = v; }

    public double getAcoBeta() { return acoBeta; }
    public void setAcoBeta(double v) { this.acoBeta = v; }

    public int getAcoIterations() { return acoIterations; }
    public void setAcoIterations(int v) { this.acoIterations = v; }

    // ==================================================================
    //  STATE ACCESSORS (ผูกกับ MapPanel สำหรับวาดภาพ)
    // ==================================================================

    public CrisisGraph getGraph() { return graph; }
    public Node getDepot() { return depot; }
    public Node getRefugeeCamp() { return refugeeCamp; }
    public FleetManager getFleet() { return fleet; }
    public List<Edge> getOptimalRoute() { return optimalRoute; }
    public double getMockRouteCost() { return mockRouteCost; }

    /** true เมื่อพร้อมเริ่มสเตปถัดไปได้ (กราฟโหลดสำเร็จแล้ว) */
    public boolean isGraphLoaded() {
        return graph != null && !graph.getAllNodes().isEmpty();
    }

    // ==================================================================
    //  STEP 1: โหลดแผนที่
    // ==================================================================

    /** @return true ถ้าโหลดสำเร็จและมีข้อมูลกราฟ */
    public boolean step1_loadMap() {
        log("====================================================");
        log("🚀 ระบบจำลองการจัดเส้นทางเสบียงภาวะวิกฤต (Crisis Logistics)");
        log("====================================================");
        log("[+] กำลังเปิดอ่านไฟล์แผนที่จาก: " + mapFilePath);

        graph = GraphImporter.importMap(mapFilePath);

        if (graph.getAllNodes().isEmpty()) {
            log("[❌ System Halt] ไม่สามารถดำเนินการต่อได้เนื่องจากไม่มีข้อมูลกราฟ");
            return false;
        }

        log("[🎉 Success] โหลดกราฟสำเร็จ — จำนวนโหนด: " + graph.getAllNodes().size()
                + ", จำนวนถนน: " + graph.getAllEdges().size());
        return true;
    }

    // ==================================================================
    //  STEP 2: กำหนดคลังเสบียงและค่ายผู้ประสบภัย
    // ==================================================================

    public boolean step2_setupDepotAndCamp() {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน (Step 1)");
            return false;
        }

        List<String> nodeIds = new ArrayList<>(graph.getAllNodes().keySet());
        String depotId = nodeIds.get(0);
        String campId = nodeIds.get(nodeIds.size() - 1);

        depot = graph.getNode(depotId);
        refugeeCamp = graph.getNode(campId);

        if (depot == null || refugeeCamp == null) {
            log("[⚠️ Warning] ไม่พบโหนดสำหรับ depot หรือ refugee camp");
            return false;
        }

        depot.setDepot(true);
        refugeeCamp.addDemand("Water", demandWaterPacks);
        refugeeCamp.addDemand("Medical_Kit", demandMedicalKits);
        refugeeCamp.setTimeWindow(timeWindowStartMin, timeWindowEndMin);

        log("[📍 Setup] กำหนดคลังเสบียงที่โหนด: " + depot.getId());
        log("[📍 Setup] กำหนดค่ายประสบภัยที่โหนด: " + refugeeCamp.getId()
                + " (ต้องการน้ำ " + (int) demandWaterPacks + " แพ็ค, เวชภัณฑ์ " + (int) demandMedicalKits + " ชุด)");
        return true;
    }

    // ==================================================================
    //  STEP 3: จำลองภัยพิบัติ (น้ำท่วม)
    // ==================================================================

    public boolean step3_simulateFlood() {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน (Step 1)");
            return false;
        }

        log("[⚠️] กำลังจำลองสภาวะน้ำท่วม ณ พิกัด (" + floodLatitude + ", " + floodLongitude
                + ") รัศมี " + floodRadiusKm + " กม.");

        DisasterSimulator.simulateFloodZone(graph, floodLatitude, floodLongitude, floodRadiusKm);

        long affected = graph.getAllEdges().values().stream()
                .filter(e -> e.getRiskLevel() > 0.0)
                .count();
        log("[✓] มีเส้นทางถนนได้รับผลกระทบจากน้ำท่วมขังทั้งหมด: " + affected + " เส้นทาง");
        return true;
    }

    // ==================================================================
    //  STEP 4: จัดเตรียมกองรถกู้ภัย
    // ==================================================================

    public boolean step4_setupFleet() {
        if (depot == null) {
            log("[⚠️] กรุณากำหนดคลังเสบียงก่อน (Step 2)");
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
    //  STEP 5: หาเส้นทางที่ดีที่สุดด้วย Ant Colony Optimization
    // ==================================================================

    public boolean step5_runRouteOptimization() {
        if (depot == null || refugeeCamp == null) {
            log("[⚠️] กรุณากำหนดคลังเสบียงและค่ายประสบภัยก่อน (Step 2)");
            return false;
        }

        log("----------------------------------------------------");
        log("🤖 เริ่มต้นเฟสการคำนวณและประเมินผลเส้นทาง (Optimization Phase)");
        log("----------------------------------------------------");

        AntColonyRouter acoRouter = new AntColonyRouter(graph);
        optimalRoute = acoRouter.findOptimalRoute(depot, refugeeCamp, acoAlpha, acoBeta, acoIterations);

        if (optimalRoute == null || optimalRoute.isEmpty()) {
            log("[⚠️] ไม่พบเส้นทางที่ไปถึงปลายทางได้");
            return false;
        }

        double totalDistance = optimalRoute.stream().mapToDouble(Edge::getDistance).sum();
        log("[✓] พบเส้นทางที่ดีที่สุด ประกอบด้วยถนน " + optimalRoute.size()
                + " เส้น (ระยะทางรวม ~" + String.format("%.0f", totalDistance) + " เมตร)");
        return true;
    }

    // ==================================================================
    //  STEP 6: ประเมินค่าใช้จ่ายของเส้นทาง (ทดสอบสมการ ตามของเดิมใน Main.java)
    // ==================================================================

    public boolean step6_evaluateMockRoute() {
        if (!isGraphLoaded()) {
            log("[⚠️] กรุณาโหลดแผนที่ก่อน (Step 1)");
            return false;
        }

        List<Edge> mockRoute = new ArrayList<>();
        if (!graph.getAllEdges().isEmpty()) {
            mockRoute.add(graph.getAllEdges().values().iterator().next());
        }

        mockRouteCost = FitnessEvaluator.evaluateRouteCost(mockRoute, acoAlpha, acoBeta);
        log("📈 ค่าความสูญเสียของเส้นทาง (Route Cost Evaluation): " + mockRouteCost);
        return true;
    }

    // ==================================================================
    //  STEP 7: รายงานผลสรุป
    // ==================================================================

    public void step7_printFinalReport() {
        if (graph == null) {
            return;
        }
        long depotCount = graph.getAllNodes().values().stream().filter(Node::isDepot).count();
        long demandCount = graph.getAllNodes().values().stream().filter(n -> !n.getDemands().isEmpty()).count();

        log("----------------------------------------------------");
        log("============================================");
        log("📊 สรุปโครงข่ายวิกฤต (Crisis Graph Summary)");
        log("============================================");
        log("📍 จำนวนโหนดทั้งหมด (Nodes): " + graph.getAllNodes().size() + " จุด");
        log("🛣️ จำนวนเส้นถนนทั้งหมด (Edges): " + graph.getAllEdges().size() + " เส้น");
        log("🏢 จำนวนคลังเสบียงหลัก (Depots): " + depotCount + " แห่ง");
        log("⛺ จำนวนค่ายประสบภัยที่รอช่วยเหลือ: " + demandCount + " แห่ง");
        log("============================================");
        log("📈 ค่าความสูญเสียของเส้นทาง (Route Cost Evaluation): " + mockRouteCost);
        log("====================================================");
        log("🎉 [Complete] ระบบรันจบวงรอบการจำลองสมบูรณ์แบบครับบัดดี้!");
    }

    /** รันทุกสเตปต่อกันรวดเดียว (เทียบเท่า Main.main() เดิม) ใช้กับโหมด "Run All" */
    public void runAllSteps() {
        if (!step1_loadMap()) return;
        step2_setupDepotAndCamp();
        step3_simulateFlood();
        step4_setupFleet();
        step5_runRouteOptimization();
        step6_evaluateMockRoute();
        step7_printFinalReport();
    }
}
