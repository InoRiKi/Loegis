package src;

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

/**
 * ระบบจำลองการจัดเส้นทางเสบียงภาวะวิกฤต (Crisis Logistics Simulation)
 *
 * ลำดับการทำงาน:
 *   1. โหลดแผนที่จริง (.graphml)
 *   2. กำหนดคลังเสบียง (depot) และค่ายผู้ประสบภัย (demand node)
 *   3. จำลองภัยพิบัติ (น้ำท่วม) ลงบนแผนที่
 *   4. จัดเตรียมกองรถกู้ภัย (fleet)
 *   5. หาเส้นทางที่ดีที่สุดด้วย Ant Colony Optimization
 *   6. ประเมินค่าใช้จ่าย/ความเสี่ยงของเส้นทาง (fitness evaluation)
 *   7. พิมพ์สรุปผล
 */
public class Main {

    // ==================================================================
    //  CONFIG: ปรับค่าต่างๆ ของการจำลองได้จากตรงนี้ที่เดียว
    // ==================================================================

    /** ไฟล์แผนที่ผังเมือง (.graphml) ที่จะนำเข้า */
    private static final String MAP_FILE_PATH = "hatyai_map.graphml";

    // --- จุดน้ำท่วม (Disaster zone) ---
    private static final double FLOOD_LATITUDE = 7.0100;
    private static final double FLOOD_LONGITUDE = 100.4700;
    private static final double FLOOD_RADIUS_KM = 2.5;

    // --- ความต้องการเสบียงของค่ายผู้ประสบภัย ---
    private static final double DEMAND_WATER_PACKS = 500.0;
    private static final double DEMAND_MEDICAL_KITS = 50.0;
    private static final double TIME_WINDOW_START_MIN = 60.0;   // ต้องส่งของได้เร็วที่สุดที่นาทีนี้
    private static final double TIME_WINDOW_END_MIN = 180.0;    // และต้องส่งให้ทันก่อนนาทีนี้

    // --- กองรถกู้ภัย (Fleet) ---
    private static final int FLEET_SIZE = 2;                    // จำนวนรถที่จะส่งออกไป
    private static final double VEHICLE_CAPACITY_KG = 1000.0;   // ความสามารถในการบรรทุกต่อคัน (กก./หน่วย)

    // --- พารามิเตอร์ของ Ant Colony Optimization ---
    // alpha = ให้น้ำหนักความเร็ว/ระยะทาง, beta = ให้น้ำหนักความปลอดภัย (หลบโซนเสี่ยง)
    private static final double ACO_ALPHA = 0.6;
    private static final double ACO_BETA = 0.4;
    private static final int ACO_ITERATIONS = 100;              // จำนวนรอบจำลองของมด


    public static void main(String[] args) {
        printHeader();

        CrisisGraph graph = loadCityGraph(MAP_FILE_PATH);
        if (graph == null) {
            return; // หยุดระบบ ถ้าโหลดแผนที่ไม่สำเร็จ
        }

        Node depot = pickDepot(graph);
        Node refugeeCamp = pickRefugeeCamp(graph);
        setupSupplyDemand(depot, refugeeCamp);

        simulateFlood(graph);

        FleetManager fleet = setupFleet(depot, FLEET_SIZE, VEHICLE_CAPACITY_KG);

        List<Edge> optimalRoute = runRouteOptimization(graph, depot, refugeeCamp);

        double routeCost = evaluateMockRoute(graph);

        printFinalReport(graph, routeCost);
    }

    // ==================================================================
    //  STEP 1: โหลดแผนที่
    // ==================================================================

    private static CrisisGraph loadCityGraph(String mapFilePath) {
        CrisisGraph graph = GraphImporter.importMap(mapFilePath);

        if (graph.getAllNodes().isEmpty()) {
            System.out.println("[❌ System Halt] ไม่สามารถดำเนินการต่อได้เนื่องจากไม่มีข้อมูลกราฟ");
            return null;
        }
        return graph;
    }

    // ==================================================================
    //  STEP 2: กำหนดคลังเสบียงและค่ายผู้ประสบภัย
    // ==================================================================

    /** ใช้โหนดแรกของกราฟเป็นคลังเสบียง (depot) แบบจำลอง */
    private static Node pickDepot(CrisisGraph graph) {
        List<String> nodeIds = new ArrayList<>(graph.getAllNodes().keySet());
        String depotId = nodeIds.get(0);
        return graph.getNode(depotId);
    }

    /** ใช้โหนดสุดท้ายของกราฟเป็นค่ายผู้ประสบภัย เพื่อให้ระยะทางห่างจาก depot มากที่สุด */
    private static Node pickRefugeeCamp(CrisisGraph graph) {
        List<String> nodeIds = new ArrayList<>(graph.getAllNodes().keySet());
        String campId = nodeIds.get(nodeIds.size() - 1);
        return graph.getNode(campId);
    }

    private static void setupSupplyDemand(Node depot, Node refugeeCamp) {
        if (depot == null || refugeeCamp == null) {
            System.out.println("[⚠️ Warning] ไม่พบโหนดสำหรับ depot หรือ refugee camp");
            return;
        }

        depot.setDepot(true);

        refugeeCamp.addDemand("Water", DEMAND_WATER_PACKS);
        refugeeCamp.addDemand("Medical_Kit", DEMAND_MEDICAL_KITS);
        refugeeCamp.setTimeWindow(TIME_WINDOW_START_MIN, TIME_WINDOW_END_MIN);

        System.out.println("[📍 Setup] กำหนดคลังเสบียงที่โหนด: " + depot.getId());
        System.out.println("[📍 Setup] กำหนดค่ายประสบภัยที่โหนด: " + refugeeCamp.getId()
                + " (ต้องการน้ำ " + (int) DEMAND_WATER_PACKS + " แพ็ค)");
    }

    // ==================================================================
    //  STEP 3: จำลองภัยพิบัติ (น้ำท่วม)
    // ==================================================================

    private static void simulateFlood(CrisisGraph graph) {
        DisasterSimulator.simulateFloodZone(graph, FLOOD_LATITUDE, FLOOD_LONGITUDE, FLOOD_RADIUS_KM);
    }

    // ==================================================================
    //  STEP 4: จัดเตรียมกองรถกู้ภัย
    // ==================================================================

    private static FleetManager setupFleet(Node depot, int fleetSize, double vehicleCapacity) {
        FleetManager fleet = new FleetManager();

        for (int i = 1; i <= fleetSize; i++) {
            String vehicleId = String.format("Rescue_Truck_%02d", i);
            fleet.addVehicle(vehicleId, vehicleCapacity, depot);
        }

        System.out.println("[🚚 Fleet] สแตนบายรถกู้ภัยจำนวน " + fleet.getAllVehicles().size() + " คัน ณ คลังเสบียง");
        return fleet;
    }

    // ==================================================================
    //  STEP 5: หาเส้นทางที่ดีที่สุดด้วย Ant Colony Optimization
    // ==================================================================

    private static List<Edge> runRouteOptimization(CrisisGraph graph, Node depot, Node refugeeCamp) {
        System.out.println("\n----------------------------------------------------");
        System.out.println("🤖 เริ่มต้นเฟสการคำนวณและประเมินผลเส้นทาง (Optimization Phase)");
        System.out.println("----------------------------------------------------");

        AntColonyRouter acoRouter = new AntColonyRouter(graph);
        return acoRouter.findOptimalRoute(depot, refugeeCamp, ACO_ALPHA, ACO_BETA, ACO_ITERATIONS);
    }

    // ==================================================================
    //  STEP 6: ประเมินค่าใช้จ่ายของเส้นทาง (ตัวอย่างทดสอบสมการ)
    // ==================================================================

    /**
     * ดึง edge แรกที่พบในกราฟมาใช้เป็นเส้นทางจำลอง เพื่อทดสอบการคำนวณของ FitnessEvaluator
     * (ไม่ใช่เส้นทางจริงที่ ACO หาได้ — ใช้สำหรับตรวจสอบสมการเท่านั้น)
     */
    private static double evaluateMockRoute(CrisisGraph graph) {
        List<Edge> mockRoute = new ArrayList<>();

        if (!graph.getAllEdges().isEmpty()) {
            mockRoute.add(graph.getAllEdges().values().iterator().next());
        }

        return FitnessEvaluator.evaluateRouteCost(mockRoute, ACO_ALPHA, ACO_BETA);
    }

    // ==================================================================
    //  STEP 7: รายงานผล
    // ==================================================================

    private static void printHeader() {
        System.out.println("====================================================");
        System.out.println("🚀 ระบบจำลองการจัดเส้นทางเสบียงภาวะวิกฤต (Crisis Logistics)");
        System.out.println("====================================================\n");
    }

    private static void printFinalReport(CrisisGraph graph, double routeCost) {
        System.out.println("\n----------------------------------------------------");
        graph.printGraphSummary();
        System.out.println("📈 ค่าความสูญเสียของเส้นทาง (Route Cost Evaluation): " + routeCost);
        System.out.println("====================================================");
        System.out.println("🎉 [Complete] ระบบรันจบวงรอบการจำลองสมบูรณ์แบบครับบัดดี้!");
    }
}