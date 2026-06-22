package src.model;

public class Edge {
    private String id;
    private Node source;
    private Node target;
    private double distance; // ระยะทางจริงจาก OSM (เมตร)

    // มิติข้อมูลภาวะวิกฤต (Dynamic Parameters)
    private double riskLevel;        // 0.0 (ปลอดภัย) ถึง 1.0 (ทางขาด/อันตรายสูงสุด)
    private double travelTimeFactor; // ตัวคูณเวลา (1.0 = ปกติ, 5.0 = น้ำท่วมรถเคลื่อนตัวช้าลง 5 เท่า)

    public Edge(String id, Node source, Node target, double distance) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.distance = distance;
        this.riskLevel = 0.0;
        this.travelTimeFactor = 1.0;
    }

    // ฟังก์ชันเด็ดสำหรับคิดค่า Weight หลายมิติ (Multi-Objective Weight)
    // สมการ: Weight = (alpha * Distance * TimeFactor) + (beta * RiskLevel)
    public double getDynamicWeight(double alpha, double beta) {
        return (alpha * this.distance * this.travelTimeFactor) + (beta * this.riskLevel * 1000.0);
        // คูณ 1000 เพื่อปรับสเกลค่าความเสี่ยงให้อยู่ในระดับเดียวกับระยะทาง (เมตร)
    }

    // --- Getters and Setters ---
    public String getId() { return id; }
    public Node getSource() { return source; }
    public Node getTarget() { return target; }
    public double getDistance() { return distance; }
    public double getRiskLevel() { return riskLevel; }
    public void setRiskLevel(double riskLevel) { this.riskLevel = riskLevel; }
    public double getTravelTimeFactor() { return travelTimeFactor; }
    public void setTravelTimeFactor(double factor) { this.travelTimeFactor = factor; }
}