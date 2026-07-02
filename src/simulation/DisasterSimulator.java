package src.simulation;

import src.model.CrisisGraph;
import src.model.Edge;

import java.util.List;

/**
 * DisasterSimulator
 * -----------------
 * ใช้จำลองน้ำท่วมบนโครงข่ายถนน
 *
 * เวอร์ชันนี้รองรับ "น้ำท่วมหลายจุด"
 * โดยแต่ละจุดสามารถกำหนดระดับน้ำได้เอง:
 *   - SHALLOW = น้ำตื้น
 *   - MEDIUM  = น้ำระดับกลาง
 *   - DEEP    = น้ำลึกจนรถผ่านไม่ได้
 *
 * ถ้าถนนเส้นเดียวโดนหลายจุดน้ำท่วมพร้อมกัน
 * ระบบจะเลือก "ระดับที่รุนแรงที่สุด" ให้ถนนเส้นนั้น
 */
public class DisasterSimulator {

    public enum FloodLevel {
        SHALLOW,
        MEDIUM,
        DEEP
    }

    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    private static int levelSeverity(FloodLevel level) {
        return switch (level) {
            case SHALLOW -> 1;
            case MEDIUM -> 2;
            case DEEP -> 3;
        };
    }

    private static void applyLevelToEdge(Edge edge, FloodLevel level) {
        switch (level) {
            case SHALLOW -> {
                edge.setRiskLevel(0.3);
                edge.setTravelTimeFactor(1.5);
                edge.setPassable(true); // รถผ่านได้
            }

            case MEDIUM -> {
                edge.setRiskLevel(0.6);
                edge.setTravelTimeFactor(2.5);
                edge.setPassable(true); // ยังผ่านได้ แต่ควรใช้เรือ
            }

            case DEEP -> {
                edge.setRiskLevel(1.0);
                edge.setTravelTimeFactor(3.5);
                edge.setPassable(true); // เรือผ่านได้
            }
        }
    }

    /**
     * จำลองน้ำท่วม 1 จุด
     * ใช้ได้เหมือนระบบเดิม แต่เพิ่ม level เข้าไป
     */
    public static void simulateFloodZone(
            CrisisGraph graph,
            double floodLat,
            double floodLon,
            double radiusKm,
            FloodLevel level
    ) {
        simulateFloodZones(
                graph,
                List.of(new FloodZone(floodLat, floodLon, radiusKm, level))
        );
    }

    /**
     * จำลองน้ำท่วมหลายจุด
     *
     * หลักการ:
     *   1. รีเซ็ตถนนทั้งหมดให้กลับเป็นปกติก่อน
     *   2. วนดูถนนทุกเส้น
     *   3. เช็กว่าถนนเส้นนั้นอยู่ในรัศมีของ FloodZone จุดไหนบ้าง
     *   4. ถ้าโดนหลายจุด ให้ใช้ระดับที่รุนแรงที่สุด
     *   5. นำระดับนั้นไปปรับค่า risk / time / passable ของถนน
     */
    public static void simulateFloodZones(CrisisGraph graph, List<FloodZone> zones) {
        if (graph == null || zones == null || zones.isEmpty()) {
            System.out.println("[⚠️] ไม่มีข้อมูลจุดน้ำท่วมสำหรับจำลอง");
            return;
        }

        graph.resetGraphEnvironment();

        int affectedEdges = 0;
        int blockedEdges = 0;

        for (Edge edge : graph.getAllEdges().values()) {
            double edgeLat = (edge.getSource().getLatitude()
                    + edge.getTarget().getLatitude()) / 2.0;

            double edgeLon = (edge.getSource().getLongitude()
                    + edge.getTarget().getLongitude()) / 2.0;

            FloodLevel strongestLevel = null;

            for (FloodZone zone : zones) {
                double distanceToFlood = calculateDistance(
                        zone.getLatitude(),
                        zone.getLongitude(),
                        edgeLat,
                        edgeLon
                );

                if (distanceToFlood <= zone.getRadiusKm()) {
                    if (strongestLevel == null
                            || levelSeverity(zone.getLevel()) > levelSeverity(strongestLevel)) {
                        strongestLevel = zone.getLevel();
                    }
                }
            }

            if (strongestLevel != null) {
                applyLevelToEdge(edge, strongestLevel);
                affectedEdges++;

                if (!edge.isPassable()) {
                    blockedEdges++;
                }
            }
        }

        System.out.println("[✓] จำลองน้ำท่วมหลายจุดสำเร็จ");
        System.out.println("[✓] จำนวนจุดน้ำท่วม: " + zones.size() + " จุด");
        System.out.println("[✓] ถนนได้รับผลกระทบทั้งหมด: " + affectedEdges + " เส้น");
        System.out.println("[⛔] ถนนที่รถผ่านไม่ได้: " + blockedEdges + " เส้น");
    }

    /**
     * กันโค้ดเก่าเรียกแล้วพัง
     * ถ้าไม่ได้ระบุระดับน้ำ จะใช้ MEDIUM เป็นค่าเริ่มต้น
     */
    public static void simulateFloodZone(
            CrisisGraph graph,
            double floodLat,
            double floodLon,
            double radiusKm
    ) {
        simulateFloodZone(graph, floodLat, floodLon, radiusKm, FloodLevel.MEDIUM);
    }
}