package src.io;

import src.model.CrisisGraph;
import src.model.Node;
import src.model.Edge;

import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.nio.graphml.GraphMLImporter;
import org.jgrapht.util.SupplierUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * GraphImporter
 * -------------
 * คลาสนี้ใช้สำหรับอ่านไฟล์แผนที่ .graphml ที่ export มาจาก OSMnx / OpenStreetMap
 * แล้วแปลงข้อมูลเป็น CrisisGraph ที่ระบบ Loegis ใช้งานได้
 *
 * สิ่งที่คลาสนี้ทำ:
 *   1. อ่านไฟล์ GraphML
 *   2. ดึงพิกัดของ node จากค่า x / y
 *      - x = longitude
 *      - y = latitude
 *   3. สร้าง Node ลงใน CrisisGraph
 *   4. สร้าง Edge หรือถนนลงใน CrisisGraph
 *   5. รองรับ Emergency Mode:
 *      - respectOneWay = true  → เคารพทิศทางถนนจริง / One-way
 *      - respectOneWay = false → สร้างถนนย้อนกลับเพิ่ม เพื่อให้รถกู้ภัยย้อนศรได้
 */
public class GraphImporter {

    /**
     * โหลดแผนที่โดยกำหนดว่าจะเคารพ One-way หรือไม่
     *
     * @param filepath       path ของไฟล์ .graphml
     * @param respectOneWay  true = เคารพ One-way, false = Emergency Mode / ย้อนศรได้
     */
    public static CrisisGraph importMap(String filepath, boolean respectOneWay) {
        System.out.println("[+] กำลังเปิดอ่านไฟล์แผนที่จาก: " + filepath);
        System.out.println("[🚦 Traffic Mode] respectOneWay = " + respectOneWay);

        CrisisGraph crisisGraph = new CrisisGraph();

        /*
         * Supplier สำหรับสร้าง id ของ vertex ชั่วคราวใน JGraphT
         *
         * ห้ามใช้ () -> "" เพราะจะคืนค่า id ซ้ำทุกครั้ง
         * ถ้า id ซ้ำ vertex ตัวหลัง ๆ จะถูกมองว่าเป็นตัวเดิม
         */
        Supplier<String> vertexSupplier = SupplierUtil.createStringSupplier();

        /*
         * ใช้ DirectedPseudograph เพราะข้อมูลถนนจริงจาก OSMnx
         * อาจมี edge หลายเส้นระหว่าง node คู่เดียวกัน
         * และอาจมี self-loop บางกรณี
         */
        Graph<String, org.jgrapht.graph.DefaultEdge> tempGraph =
                new DirectedPseudograph<>(
                        vertexSupplier,
                        org.jgrapht.graph.DefaultEdge::new,
                        false
                );

        GraphMLImporter<String, org.jgrapht.graph.DefaultEdge> importer =
                new GraphMLImporter<>();

        /*
         * ปิด schema validation
         *
         * เหตุผล:
         * ไฟล์จาก OSMnx บางครั้งมี id ของ edge ซ้ำ
         * ถ้าเปิด schema validation อาจเจอ error เช่น Duplicate unique value
         */
        importer.setSchemaValidation(false);

        /*
         * เก็บพิกัดของ node ชั่วคราวระหว่าง import
         *
         * lats = nodeId -> latitude
         * lons = nodeId -> longitude
         */
        Map<String, Double> lats = new HashMap<>();
        Map<String, Double> lons = new HashMap<>();

        /*
         * อ่าน attribute ของ vertex จาก GraphML
         *
         * ในไฟล์ OSMnx:
         *   y = latitude
         *   x = longitude
         */
        importer.addVertexAttributeConsumer((p, attr) -> {
            String nodeId = p.getFirst();
            String attrName = p.getSecond();
            String attrValue = attr.getValue();

            if (attrName.equals("y")) {
                lats.put(nodeId, Double.parseDouble(attrValue));
            } else if (attrName.equals("x")) {
                lons.put(nodeId, Double.parseDouble(attrValue));
            }
        });

        try {
            File file = new File(filepath);

            if (!file.exists()) {
                System.out.println("[❌ Error] หาไฟล์ไม่เจอในพิกัด: " + file.getAbsolutePath());
                return crisisGraph;
            }

            /*
             * อ่านไฟล์ GraphML เข้าสู่ tempGraph ก่อน
             */
            importer.importGraph(tempGraph, file);

            /*
             * แปลง vertex ใน tempGraph เป็น Node ของระบบ Loegis
             */
            for (String vId : tempGraph.vertexSet()) {
                double lat = lats.getOrDefault(vId, 0.0);
                double lon = lons.getOrDefault(vId, 0.0);

                Node node = new Node(vId, lat, lon);
                crisisGraph.addNode(node);
            }

            /*
             * แปลง edge ใน tempGraph เป็น Edge ของระบบ Loegis
             */
            int edgeCounter = 0;
            int skippedSelfLoops = 0;

            for (org.jgrapht.graph.DefaultEdge e : tempGraph.edgeSet()) {
                String sourceId = tempGraph.getEdgeSource(e);
                String targetId = tempGraph.getEdgeTarget(e);

                /*
                 * ข้าม self-loop
                 *
                 * self-loop คือถนนที่ source == target
                 * ไม่มีประโยชน์กับการหาเส้นทางจากจุดหนึ่งไปอีกจุดหนึ่ง
                 */
                if (sourceId.equals(targetId)) {
                    skippedSelfLoops++;
                    continue;
                }

                Node sourceNode = crisisGraph.getNode(sourceId);
                Node targetNode = crisisGraph.getNode(targetId);

                if (sourceNode != null && targetNode != null) {

                    /*
                     * 1) สร้างถนนหลักตามทิศทางจริงจาก OSM
                     *
                     * ถ้า OSM บอกว่า A -> B
                     * ระบบจะเพิ่ม edge A -> B
                     *
                     * ตรงนี้สำคัญมาก ห้ามคอมเมนต์ออก
                     */
                    // ==========================
// ถนนจริง
// ==========================
                    Edge edge = new Edge(
                            "e_" + edgeCounter++,
                            sourceNode,
                            targetNode,
                            150.0
                    );

                    edge.setReverseEdge(false);
                    edge.setTrafficAllowed(true);

                    crisisGraph.addEdge(edge);

// ==========================
// ถนนย้อนศร
// สร้างไว้ตลอด
// ==========================
                    Edge reverseEdge = new Edge(
                            "e_" + edgeCounter++,
                            targetNode,
                            sourceNode,
                            150.0
                    );

                    reverseEdge.setReverseEdge(true);

// เริ่มต้นปิดไว้
                    reverseEdge.setTrafficAllowed(false);

                    crisisGraph.addEdge(reverseEdge);
                }
            }

            if (skippedSelfLoops > 0) {
                System.out.println("[ℹ️ Info] ข้าม self-loop ไป "
                        + skippedSelfLoops
                        + " เส้น");
            }

            /*
             * ใช้ crisisGraph.getAllEdges().size()
             * เพราะถ้า Emergency Mode เปิดอยู่
             * จำนวนถนนจะรวม reverse edge แล้ว
             */
            System.out.println("[🎉 Success] อ่านและแปลงไฟล์ผังเมืองสำเร็จ!");
            System.out.println("    จำนวนโหนด: " + crisisGraph.getAllNodes().size());
            System.out.println("    จำนวนถนนในระบบ: " + crisisGraph.getAllEdges().size());
            System.out.println("    โหมดการจราจร: "
                    + (respectOneWay ? "เคารพ One-way" : "Emergency Mode / ย้อนศรได้"));

        } catch (Exception e) {
            System.out.println("[❌ Error] เกิดข้อผิดพลาดขณะพาร์สไฟล์ GraphML: " + e.getMessage());
            e.printStackTrace();
        }

        return crisisGraph;
    }

    /**
     * Overload สำหรับโค้ดเก่าที่ยังเรียก importMap(filepath)
     *
     * ค่าเริ่มต้น:
     *   respectOneWay = true
     * แปลว่าใช้การจราจรจริง / เคารพ One-way
     */
    public static CrisisGraph importMap(String filepath) {
        return importMap(filepath, true);
    }
}