package src.model;

import java.util.HashMap;
import java.util.Map;

public class CrisisGraph {
    // ใช้ HashMap เพื่อให้สามารถค้นหา Node และ Edge ได้อย่างรวดเร็วผ่าน ID (O(1) Time Complexity)
    private Map<String, Node> nodes;
    private Map<String, Edge> edges;

    public CrisisGraph() {
        this.nodes = new HashMap<>();
        this.edges = new HashMap<>();
    }

    // --- ฟังก์ชันเพิ่มข้อมูลเข้าสู่ระบบกราฟ ---

    public void addNode(Node node) {
        nodes.put(node.getId(), node);
    }

    public void addEdge(Edge edge) {
        edges.put(edge.getId(), edge);
    }

    // --- ฟังก์ชันค้นหาข้อมูล (Getters) ---

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

    // --- ฟังก์ชันเด็ดสำหรับงานวิจัย: การรีเซ็ตสภาวะวิกฤต (Utility Methods) ---

    /**
     * รีเซ็ตค่าความเสี่ยงและตัวคูณเวลาบนถนนทุกเส้นให้กลับมาเป็นปกติ (เคลียร์ค่าภัยพิบัติเก่า)
     */
    public void resetGraphEnvironment() {
        for (Edge edge : edges.values()) {
            edge.setRiskLevel(0.0);
            edge.setTravelTimeFactor(1.0);
        }
        System.out.println("[-] ระบบกราฟสิ่งแวดล้อมถูกรีเซ็ตเป็นสถานะปกติเรียบร้อย");
    }

    /**
     * พริ้นท์สรุปสถานะปัจจุบันของกราฟออกมาตรวจสอบ (สำหรับโชว์หน้า Console)
     */
    public void printGraphSummary() {
        long depotCount = nodes.values().stream().filter(Node::isDepot).count();
        long demandCount = nodes.values().stream().filter(n -> !n.getDemands().isEmpty()).count();

        System.out.println("============================================");
        System.out.println("📊 สรุปโครงข่ายวิกฤต (Crisis Graph Summary)");
        System.out.println("============================================");
        System.out.println("📍 จำนวนโหนดทั้งหมด (Nodes): " + nodes.size() + " จุด");
        System.out.println("🛣️ จำนวนเส้นถนนทั้งหมด (Edges): " + edges.size() + " เส้น");
        System.out.println("🏢 จำนวนคลังเสบียงหลัก (Depots): " + depotCount + " แห่ง");
        System.out.println("⛺ จำนวนค่ายประสบภัยที่รอช่วยเหลือ: " + demandCount + " แห่ง");
        System.out.println("============================================");
    }
}