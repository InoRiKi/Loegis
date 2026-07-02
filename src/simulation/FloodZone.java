package src.simulation;

/**
 * FloodZone
 * ---------
 * เก็บข้อมูล "จุดน้ำท่วม 1 จุด" ที่ผู้ใช้กำหนดบนแผนที่
 *
 * ใช้สำหรับระบบน้ำท่วมหลายจุด:
 *   - แต่ละจุดมีพิกัดของตัวเอง
 *   - แต่ละจุดมีรัศมีของตัวเอง
 *   - แต่ละจุดมีระดับน้ำของตัวเอง เช่น ตื้น / กลาง / ลึก
 */
public class FloodZone {
    private double latitude;
    private double longitude;
    private double radiusKm;
    private DisasterSimulator.FloodLevel level;

    public FloodZone(
            double latitude,
            double longitude,
            double radiusKm,
            DisasterSimulator.FloodLevel level
    ) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusKm = radiusKm;
        this.level = level;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getRadiusKm() {
        return radiusKm;
    }

    public DisasterSimulator.FloodLevel getLevel() {
        return level;
    }
}