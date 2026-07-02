package src.model;

import java.util.HashMap;
import java.util.Map;

/**
 * CrisisGraph
 * -----------
 * คลาสนี้เป็นโครงข่ายหลักของระบบ Loegis
 *
 * ทำหน้าที่เก็บข้อมูลทั้งหมดของแผนที่ในรูปแบบกราฟ
 * โดยประกอบด้วย:
 *   - Node = จุดบนแผนที่ เช่น ทางแยก คลังเสบียง ค่ายผู้ประสบภัย
 *   - Edge = เส้นถนนที่เชื่อมระหว่าง Node
 *
 * ใช้ HashMap เพื่อให้ค้นหา Node และ Edge จาก id ได้รวดเร็ว
 * เหมาะกับการคำนวณเส้นทางและการอัปเดตสถานะภัยพิบัติแบบ real-time
 */
public class CrisisGraph {
    private Map<String, Node> nodes;
    private Map<String, Edge> edges;

    public CrisisGraph() {
        this.nodes = new HashMap<>();
        this.edges = new HashMap<>();
    }

    // --- ฟังก์ชันเพิ่มข้อมูลเข้าสู่กราฟ ---

    public void addNode(Node node) {
        nodes.put(node.getId(), node);
    }

    public void addEdge(Edge edge) {
        edges.put(edge.getId(), edge);
    }



    // --- ฟังก์ชันค้นหาข้อมูล ---

    public Node getNode(String id) {
        return nodes.get(id);
    }

    public Edge getEdge(String id) {
        return edges.get(id);
    }

    public Map<String, Node> getAllNodes() {
        return nodes;
    }

    public Map<String, Edge> getAllEdges() {
        return edges;
    }

    /**
     * รีเซ็ตสภาพแวดล้อมของกราฟให้กลับสู่สภาวะปกติ
     *
     * ใช้เมื่อ:
     *   - เริ่มการจำลองใหม่
     *   - เปลี่ยนตำแหน่งน้ำท่วม
     *   - ต้องการล้างค่าความเสี่ยงเดิม
     *
     * สิ่งที่รีเซ็ต:
     *   - riskLevel กลับเป็น 0.0
     *   - travelTimeFactor กลับเป็น 1.0
     *   - passable กลับเป็น true
     *
     * หมายความว่า ถนนทุกเส้นกลับมาใช้งานได้ตามปกติ
     */
    public void resetGraphEnvironment() {
        for (Edge edge : edges.values()) {
            edge.setRiskLevel(0.0);
            edge.setTravelTimeFactor(1.0);
            edge.setPassable(true);
        }

        System.out.println("[-] ระบบกราฟสิ่งแวดล้อมถูกรีเซ็ตเป็นสถานะปกติเรียบร้อย");
    }

    /**
     * แสดงสรุปข้อมูลของกราฟใน Console
     *
     * ใช้สำหรับตรวจสอบหลังโหลดแผนที่หรือหลังจำลองสถานการณ์
     * เช่น จำนวนโหนด จำนวนถนน จำนวนถนนที่ถูกปิดจากน้ำท่วม
     */
    public void setRespectOneWay(boolean respectOneWay) {
        for (Edge edge : edges.values()) {
            if (edge.isReverseEdge()) {
                edge.setTrafficAllowed(!respectOneWay);
            } else {
                edge.setTrafficAllowed(true);
            }
        }

        System.out.println("[🚦] เปลี่ยนโหมดจราจรเป็น: "
                + (respectOneWay ? "เคารพ One-way" : "Emergency Mode / ย้อนศรได้"));
    }

    public void printGraphSummary() {
        long depotCount = nodes.values().stream()
                .filter(Node::isDepot)
                .count();

        long demandCount = nodes.values().stream()
                .filter(n -> !n.getDemands().isEmpty())
                .count();

        long blockedRoadCount = edges.values().stream()
                .filter(e -> !e.isPassable())
                .count();

        System.out.println("============================================");
        System.out.println("📊 สรุปโครงข่ายวิกฤต (Crisis Graph Summary)");
        System.out.println("============================================");
        System.out.println("📍 จำนวนโหนดทั้งหมด (Nodes): " + nodes.size() + " จุด");
        System.out.println("🛣️ จำนวนเส้นถนนทั้งหมด (Edges): " + edges.size() + " เส้น");
        System.out.println("⛔ จำนวนถนนที่รถผ่านไม่ได้: " + blockedRoadCount + " เส้น");
        System.out.println("🏢 จำนวนคลังเสบียงหลัก (Depots): " + depotCount + " แห่ง");
        System.out.println("⛺ จำนวนค่ายประสบภัยที่รอช่วยเหลือ: " + demandCount + " แห่ง");
        System.out.println("============================================");
    }
}