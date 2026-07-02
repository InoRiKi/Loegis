package src.model;

public class Edge {
    private String id;
    private Node source;
    private Node target;
    private double distance;

    private double riskLevel;
    private double travelTimeFactor;

    // สภาพถนนจริง เช่น น้ำลึก / ถนนปิด
    private boolean passable;

    // อนุญาตตามกฎจราจรไหม
    private boolean trafficAllowed;

    // true = เส้นย้อนศรที่ระบบสร้างเพิ่ม
    private boolean reverseEdge;

    public Edge(String id, Node source, Node target, double distance) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.distance = distance;

        this.riskLevel = 0.0;
        this.travelTimeFactor = 1.0;
        this.passable = true;
        this.trafficAllowed = true;
        this.reverseEdge = false;
    }

    public double getDynamicWeight(double alpha, double beta) {
        if (!isPassable()) {
            return Double.POSITIVE_INFINITY;
        }

        return (alpha * this.distance * this.travelTimeFactor)
                + (beta * this.riskLevel * 100.0);
    }

    public String getId() { return id; }
    public Node getSource() { return source; }
    public Node getTarget() { return target; }
    public double getDistance() { return distance; }

    public double getRiskLevel() { return riskLevel; }
    public void setRiskLevel(double riskLevel) { this.riskLevel = riskLevel; }

    public double getTravelTimeFactor() { return travelTimeFactor; }
    public void setTravelTimeFactor(double travelTimeFactor) {
        this.travelTimeFactor = travelTimeFactor;
    }

    public double getSafetyScore() {
        if (!isPassable()) return 0.0;

        if (riskLevel <= 0.0) return 1.0;  // ถนนปกติ
        if (riskLevel < 0.45) return 0.8;  // น้ำตื้น
        if (riskLevel < 0.85) return 0.5;  // น้ำกลาง

        return 0.35; // น้ำลึก แต่เรือยังช่วยได้
    }

    // ใช้โดย Dijkstra / ACO
    public boolean isPassable() {
        return passable && trafficAllowed;
    }

    // ใช้กับน้ำท่วม / ถนนเสีย
    public void setPassable(boolean passable) {
        this.passable = passable;
    }

    public boolean isRoadPassable() {
        return passable;
    }

    // ใช้กับโหมดจราจร
    public boolean isTrafficAllowed() {
        return trafficAllowed;
    }

    public void setTrafficAllowed(boolean trafficAllowed) {
        this.trafficAllowed = trafficAllowed;
    }

    public boolean isReverseEdge() {
        return reverseEdge;
    }

    public void setReverseEdge(boolean reverseEdge) {
        this.reverseEdge = reverseEdge;
    }

    public String getRescueVehicleType() {
        if (riskLevel <= 0.0) {
            return "TRUCK";
        }

        if (riskLevel < 0.45) {
            return "TRUCK"; // น้ำตื้น ใช้รถ
        }

        return "BOAT"; // น้ำกลางถึงลึก ใช้เรือ
    }
}