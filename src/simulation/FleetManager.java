package src.simulation;

import src.model.Node;
import java.util.ArrayList;
import java.util.List;

public class FleetManager {

    // คลาสย่อย (Inner Class) สำหรับเก็บโปรไฟล์ของรถแต่ละคัน
    public static class Vehicle {
        private String id;
        private double capacity;    // น้ำหนักบรรทุกสูงสุด (กก. หรือ จำนวนชิ้น)
        private double currentLoad; // น้ำหนักที่บรรทุกอยู่ ณ ปัจจุบัน
        private Node startNode;     // จุดสตาร์ท (คลังเสบียง)

        public Vehicle(String id, double capacity, Node startNode) {
            this.id = id;
            this.capacity = capacity;
            this.startNode = startNode;
            this.currentLoad = 0.0;
        }

        public String getId() { return id; }
        public double getCapacity() { return capacity; }
        public Node getStartNode() { return startNode; }
        public double getCurrentLoad() { return currentLoad; }
        public void setCurrentLoad(double load) { this.currentLoad = load; }
    }

    private List<Vehicle> vehicles;

    public FleetManager() {
        this.vehicles = new ArrayList<>();
    }

    public void addVehicle(String id, double capacity, Node startNode) {
        vehicles.add(new Vehicle(id, capacity, startNode));
    }

    public List<Vehicle> getAllVehicles() {
        return vehicles;
    }
}