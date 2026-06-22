package src.model;

import java.util.HashMap;
import java.util.Map;

public class Node {
    private String id;
    private double latitude;
    private double longitude;

    // เก็บความต้องการเสบียงแยกประเภท เช่น <"Water", 500.0>
    private Map<String, Double> demands;

    // เงื่อนไขเวลาเดดไลน์ (Time Windows)
    private double timeWindowStart;
    private double timeWindowEnd;
    private boolean isDepot; // true = คลังเสบียง, false = จุดประสบภัย/ทางแยก

    public Node(String id, double latitude, double longitude) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
        this.demands = new HashMap<>();
        this.timeWindowStart = 0.0;
        this.timeWindowEnd = Double.MAX_VALUE;
        this.isDepot = false;
    }

    // --- Getters and Setters ---
    public String getId() { return id; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Map<String, Double> getDemands() { return demands; }
    public void addDemand(String type, double amount) { this.demands.put(type, amount); }
    public double getTimeWindowStart() { return timeWindowStart; }
    public void setTimeWindow(double start, double end) { this.timeWindowStart = start; this.timeWindowEnd = end; }
    public double getTimeWindowEnd() { return timeWindowEnd; }
    public boolean isDepot() { return isDepot; }
    public void setDepot(boolean depot) { isDepot = depot; }
}