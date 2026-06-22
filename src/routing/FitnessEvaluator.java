package src.routing;

import src.model.Node;
import src.model.Edge;
import java.util.List;

public class FitnessEvaluator {

    /**
     * ฟังก์ชันคำนวณคะแนนรวมของเส้นทางเดินรถคันหนึ่ง (ยิ่งน้อยยิ่งดี)
     * Z = (alpha * Total Time) + (beta * Total Risk)
     */
    public static double evaluateRouteCost(List<Edge> route, double alpha, double beta) {
        double totalDistance = 0.0;
        double totalRisk = 0.0;
        double totalTravelTimeFactor = 0.0;

        for (Edge edge : route) {
            totalDistance += edge.getDistance();
            totalRisk += edge.getRiskLevel();
            totalTravelTimeFactor += edge.getTravelTimeFactor();
        }

        // สมมุติคิดความเร็วเฉลี่ยรถกู้ภัยที่ 40 กม./ชม. (ประมาณ 11 เมตร/วินาที)
        double baseTime = totalDistance / 11.0;
        double actualTime = baseTime * (totalTravelTimeFactor / route.size());

        // สมการ Multi-Objective Fitness
        double fitnessScore = (alpha * actualTime) + (beta * totalRisk * 500.0);

        return fitnessScore;
    }
}