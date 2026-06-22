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

public class GraphImporter {

    public static CrisisGraph importMap(String filepath) {
        System.out.println("[+] กำลังเปิดอ่านไฟล์แผนที่จาก: " + filepath);

        CrisisGraph crisisGraph = new CrisisGraph();

        // ✅ ใช้ SupplierUtil สร้าง id ใหม่ไม่ซ้ำกันทุกครั้งที่ importer เรียก addVertex()
        //    (ห้ามใช้ () -> "" เพราะจะคืนค่าซ้ำตัวเดิมตลอด ทำให้ addVertex() ตัวที่ 2 เป็นต้นไปถูกมองว่าซ้ำแล้วถูกข้ามแบบเงียบ ๆ)
        Supplier<String> vertexSupplier = SupplierUtil.createStringSupplier();

        // ✅ ใช้ DirectedPseudograph แทน SimpleDirectedGraph เพราะข้อมูลถนนจริงจาก OSMnx
        //    มักมี self-loop (ถนนวนกลับจุดเดิม เช่น roundabout เล็ก ๆ หรือ noise จาก OSM)
        //    SimpleDirectedGraph จะ throw "loops not allowed" ทันทีที่เจอ edge แบบนี้
        Graph<String, org.jgrapht.graph.DefaultEdge> tempGraph =
                new DirectedPseudograph<>(
                        vertexSupplier,
                        org.jgrapht.graph.DefaultEdge::new,
                        false
                );

        GraphMLImporter<String, org.jgrapht.graph.DefaultEdge> importer = new GraphMLImporter<>();

        // 🔥 จุดสำคัญ: สั่งปิดการตรวจสอบ Schema เพื่อข้ามเออเร่อ "Duplicate unique value [0]"
        //    (OSMnx export edge id ซ้ำได้ตามธรรมชาติ เช่น ถนนสองทิศทางบนเส้นเดียวกัน)
        importer.setSchemaValidation(false);

        // ตัวช่วยจำพิกัดชั่วขณะขณะพาร์สไฟล์ XML
        Map<String, Double> lats = new HashMap<>();
        Map<String, Double> lons = new HashMap<>();

        // คอยแกะค่า x (Longitude) และ y (Latitude) ที่ OSMnx เซฟมาในไฟล์
        // Signature จริง: BiConsumer<Pair<V, String>, Attribute>
        //   - p.getFirst()  -> V (vertex id)        ใช้ตรง ๆ
        //   - p.getSecond() -> String (ชื่อ attribute เช่น "x", "y")  ใช้ตรง ๆ ไม่ต้อง .getName()
        //   - attr          -> Attribute object     ต้องเรียก .getValue() เพื่อเอาค่าออกมา
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

            // อ่านไฟล์เข้ากราฟชั่วคราว
            importer.importGraph(tempGraph, file);

            // ย้ายข้อมูลโหนดเข้าสู่ระบบ CrisisGraph
            for (String vId : tempGraph.vertexSet()) {
                double lat = lats.getOrDefault(vId, 0.0);
                double lon = lons.getOrDefault(vId, 0.0);

                // ⚠️ เช็คลำดับ parameter ของ constructor Node ให้ตรงกับนิยามจริงในคลาส model.Node
                //    (โค้ดนี้สมมุติว่าเป็น (id, lat, lon) ตามที่เขียนไว้เดิม)
                Node node = new Node(vId, lat, lon);
                crisisGraph.addNode(node);
            }

            // ย้ายข้อมูลถนนและรัน ID ใหม่เพื่อความปลอดภัย
            int edgeCounter = 0;
            int skippedSelfLoops = 0;
            for (org.jgrapht.graph.DefaultEdge e : tempGraph.edgeSet()) {
                String sourceId = tempGraph.getEdgeSource(e);
                String targetId = tempGraph.getEdgeTarget(e);

                // ⛔ ข้าม self-loop (ถนนที่วนกลับจุดเริ่มต้นเดิม) เพราะไม่มีประโยชน์
                //    ต่อการคำนวณเส้นทางด้วย Ant Colony Optimization
                if (sourceId.equals(targetId)) {
                    skippedSelfLoops++;
                    continue;
                }

                Node sourceNode = crisisGraph.getNode(sourceId);
                Node targetNode = crisisGraph.getNode(targetId);

                if (sourceNode != null && targetNode != null) {
                    // กำหนดระยะทางสมมุติเริ่มต้น 150 เมตร หรือจะดึงค่าจริงมาคิดภายหลังได้
                    Edge edge = new Edge("e_" + edgeCounter++, sourceNode, targetNode, 150.0);
                    crisisGraph.addEdge(edge);
                }
            }

            if (skippedSelfLoops > 0) {
                System.out.println("[ℹ️ Info] ข้าม self-loop ไป " + skippedSelfLoops + " เส้น (ถนนที่วนกลับจุดเดิม)");
            }

            System.out.println("[🎉 Success] อ่านและแปลงไฟล์ผังเมืองเข้าสู่ระบบโมเดลสำเร็จ! จำนวนโหนด: "
                    + tempGraph.vertexSet().size() + ", จำนวนถนน: " + tempGraph.edgeSet().size());

        } catch (Exception e) {
            System.out.println("[❌ Error] เกิดข้อผิดพลาดขณะพาร์สไฟล์ GraphML: " + e.getMessage());
            e.printStackTrace();
        }

        return crisisGraph;
    }
}