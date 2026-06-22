package src.simulation;

import src.model.CrisisGraph;
import src.model.Edge;

public class DisasterSimulator {

    /**
     * ฟังก์ชันคำนวณระยะทางแบบเส้นตรงระหว่างพิกัดดาวเทียม (Haversine Formula)
     * เอาไว้ใช้วัดว่าถนนเส้นนั้นอยู่ใกล้จุดน้ำท่วมกี่กิโลเมตร
     */
    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // รัศมีของโลก (กิโลเมตร)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * สั่งจำลองเขตน้ำท่วมขัง: ถ้าถนนเส้นไหนอยู่ในรัศมี จะทำให้รถวิ่งช้าลงและเสี่ยงขึ้น
     */
    public static void simulateFloodZone(CrisisGraph graph, double floodLat, double floodLon, double radiusKm) {
        System.out.println("[⚠️] กำลังจำลองสภาวะน้ำท่วม ณ พิกัด (" + floodLat + ", " + floodLon + ") รัศมี " + radiusKm + " กม.");

        int affectedEdges = 0;

        for (Edge edge : graph.getAllEdges().values()) {
            // หาพิกัดกึ่งกลางของถนนเส้นนั้นๆ
            double edgeLat = (edge.getSource().getLatitude() + edge.getTarget().getLatitude()) / 2.0;
            double edgeLon = (edge.getSource().getLongitude() + edge.getTarget().getLongitude()) / 2.0;

            double distanceToFlood = calculateDistance(floodLat, floodLon, edgeLat, edgeLon);

            // ถ้าถนนอยู่ในโซนน้ำท่วม ให้เพิ่มค่าความเสี่ยงและเวลาเดินทางช้าลง
            if (distanceToFlood <= radiusKm) {
                edge.setTravelTimeFactor(4.0); // รถวิ่งช้าลง 4 เท่า
                edge.setRiskLevel(0.7);        // ความเสี่ยงระดับสูง 0.7
                affectedEdges++;
            }
        }
        System.out.println("[✓] มีเส้นทางถนนได้รับผลกระทบจากน้ำท่วมขังทั้งหมด: " + affectedEdges + " เส้นทาง");
    }
}