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

public class Main {
    public static void main(String[] args) {
        System.out.println("====================================================");
        System.out.println("🚀 ระบบจำลองการจัดเส้นทางเสบียงภาวะวิกฤต (Crisis Logistics)");
        System.out.println("====" +
                "================================================\n");

        // 1. นำเข้าข้อมูลผังเมืองจริง (หาดใหญ่) จากไฟล์ .graphml
        String mapFilePath = "hatyai_map.graphml";
        CrisisGraph hatYaiGraph = GraphImporter.importMap(mapFilePath);

        // ตรวจสอบความปลอดภัยเบื้องต้นเผื่อโหลดไฟล์ไม่สำเร็จ
        if (hatYaiGraph.getAllNodes().isEmpty()) {
            System.out.println("[❌ System Halt] ไม่สามารถดำเนินการต่อได้เนื่องจากไม่มีข้อมูลกราฟ");
            return;
        }

        // 2. กำหนดคลังเสบียง (Depot) และค่ายผู้ประสบภัย (Demand Nodes) จำลอง
        // ดึงโหนดแรกๆ ออกมาทำเป็นจุดสมมุติเพื่อทดสอบวงจรรอบแรก
        List<String> nodeIds = new ArrayList<>(hatYaiGraph.getAllNodes().keySet());
        String depotId = nodeIds.get(0);
        String campId = nodeIds.get(nodeIds.size() - 1); // ใช้โหนดท้ายๆ เพื่อให้ระยะทางห่างกัน

        Node depot = hatYaiGraph.getNode(depotId);
        Node refugeeCamp = hatYaiGraph.getNode(campId);

        if (depot != null && refugeeCamp != null) {
            depot.setDepot(true);
            refugeeCamp.addDemand("Water", 500.0);
            refugeeCamp.addDemand("Medical_Kit", 50.0);
            refugeeCamp.setTimeWindow(60.0, 180.0); // ต้องส่งในนาทีที่ 60-180
            System.out.println("[📍 Setup] กำหนดคลังเสบียงที่โหนด: " + depotId);
            System.out.println("[📍 Setup] กำหนดค่ายประสบภัยที่โหนด: " + campId + " (ต้องการน้ำ 500 แพ็ค)");
        }

        // 3. จำลองภัยพิบัติธรรมชาติ (ถล่มน้ำท่วมลงไปบนแผนที่หาดใหญ่)
        // สมมุติพิกัดใจกลางเมืองหาดใหญ่ และรัศมีทำลายล้าง 2.5 กิโลเมตร
        double floodLatitude = 7.0100;
        double floodLongitude = 100.4700;
        double floodRadiusKm = 2.5;
        DisasterSimulator.simulateFloodZone(hatYaiGraph, floodLatitude, floodLongitude, floodRadiusKm);

        // 4. จัดเตรียมกองกำลังรถกู้ภัย (Fleet Management)
        FleetManager fleet = new FleetManager();
        fleet.addVehicle("Rescue_Truck_01", 1000.0, depot); // รถบรรทุกขนาด 1 ตัน สตาร์ทที่คลัง
        fleet.addVehicle("Rescue_Truck_02", 1000.0, depot);
        System.out.println("[🚚 Fleet] สแตนบายรถกู้ภัยจำนวน " + fleet.getAllVehicles().size() + " คัน ณ คลังเสบียง");

        System.out.println("\n----------------------------------------------------");
        System.out.println("🤖 เริ่มต้นเฟสการคำนวณและประเมินผลเส้นทาง (Optimization Phase)");
        System.out.println("----------------------------------------------------");

        // 5. ส่งโจทย์ให้ AI (Ant Colony Optimization) ประมวลผลหาเส้นทางที่ดีที่สุด
        AntColonyRouter acoRouter = new AntColonyRouter(hatYaiGraph);

        // กำหนดค่าน้ำหนักความสำคัญ (Alpha = เน้นเวลาเร็ว, Beta = เน้นความปลอดภัยหลบน้ำท่วม)
        double alpha = 0.6;
        double beta = 0.4;
        int iterations = 100; // รันมดจำลอง 100 รอบ

        List<Edge> optimalRoute = acoRouter.findOptimalRoute(depot, refugeeCamp, alpha, beta, iterations);

        // 6. การทดลองเชิงวิทยาศาสตร์: วัดค่าประสิทธิภาพ (Evaluation / Benchmarking)
        // (ตัวอย่างนี้เราดึงเส้นทางจำลองขึ้นมาเพื่อทดสอบการคำนวณของระบบ Fitness Evaluator)
        List<Edge> mockRoute = new ArrayList<>();
        // ดึง Edge แรกที่เจอในระบบมาใส่เป็นเส้นทางจำลองขำๆ เพื่อเช็คสมการ
        if (!hatYaiGraph.getAllEdges().isEmpty()) {
            mockRoute.add(hatYaiGraph.getAllEdges().values().iterator().next());
        }

        double routeCost = FitnessEvaluator.evaluateRouteCost(mockRoute, alpha, beta);

        // 7. พริ้นท์สรุปรายงานภาพรวมระบบ
        System.out.println("\n----------------------------------------------------");
        hatYaiGraph.printGraphSummary();
        System.out.println("📈 ค่าความสูญเสียของเส้นทาง (Route Cost Evaluation): " + routeCost);
        System.out.println("====================================================");
        System.out.println("🎉 [Complete] ระบบรันจบวงรอบการจำลองสมบูรณ์แบบครับบัดดี้!");
    }
}