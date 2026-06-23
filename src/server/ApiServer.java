package src.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ApiServer
 * ---------
 * เซิร์ฟเวอร์ HTTP ขนาดเล็กที่สุด ใช้ com.sun.net.httpserver.HttpServer ที่มาพร้อม JDK อยู่แล้ว
 * (ไม่ต้องพึ่ง Spring Boot หรือ framework ใหญ่ๆ — เหมาะกับโปรเจกต์นี้ที่มี API แค่ไม่กี่ endpoint)
 *
 * หน้าที่:
 *   1. เสิร์ฟไฟล์ frontend (HTML/CSS/JS) จากโฟลเดอร์ webapp/
 *   2. รับ REST API จากหน้าเว็บ แล้วเรียก SimulationState (ซึ่งห่อ logic เดิมไว้อีกชั้น)
 *   3. ส่งทุก response กลับเป็น UTF-8 เสมอ — แก้ปัญหา log ภาษาไทยอ่านไม่ได้จากเวอร์ชัน Swing เดิม
 *      (ปัญหาเดิมเกิดจาก console/compile encoding ของเครื่อง ไม่ใช่จากตัวอักษรเอง
 *       พอส่งผ่าน HTTP + JS อ่าน UTF-8 ตรงๆ ปัญหานี้หายไปโดยธรรมชาติ)
 *
 * ทุก endpoint คืนค่า Content-Type: application/json; charset=UTF-8 ชัดเจน
 */
public class ApiServer {

    private SimulationState state; // ไม่ใส่ final เพราะ handleReset ต้องสร้างตัวใหม่มาแทนทั้งก้อนได้
    private final Deque<String> logBuffer = new ArrayDeque<>(); // เก็บ log ล่าสุดไว้ส่งให้หน้าเว็บ poll ดู
    private static final int MAX_LOG_LINES = 500;

    public ApiServer() {
        this.state = new SimulationState(this::pushLog);
    }

    private synchronized void pushLog(String line) {
        logBuffer.addLast(line);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst();
        }
    }

    public void start(int port, String webRoot) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);

        server.createContext("/api/load", this::handleLoad);
        server.createContext("/api/setDepot", this::handleSetDepot);
        server.createContext("/api/setCamp", this::handleSetCamp);
        server.createContext("/api/simulateFlood", this::handleSimulateFlood);
        server.createContext("/api/setupFleet", this::handleSetupFleet);
        server.createContext("/api/runAco", this::handleRunAco);
        server.createContext("/api/state", this::handleGetState);
        server.createContext("/api/logs", this::handleGetLogs);
        server.createContext("/api/reset", this::handleReset);

        StaticFileHandler staticHandler = new StaticFileHandler(webRoot);
        server.createContext("/", staticHandler);

        server.start();
        if (!staticHandler.rootExists()) {
            System.out.println("[⚠️ Warning] ไม่พบโฟลเดอร์ webapp/ ที่ path: " + staticHandler.rootPath()
                    + " — กรุณารันคำสั่ง java จากโฟลเดอร์ loegis/ (ที่มีโฟลเดอร์ webapp/ อยู่ข้างๆ)");
        }
        System.out.println("[🌐 Server] เปิดให้บริการที่ http://localhost:" + port + "  (กด Ctrl+C เพื่อปิด)");
    }

    // ==================================================================
    //  API HANDLERS
    // ==================================================================

    private void handleLoad(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = readJsonBody(ex);
        String path = String.valueOf(body.getOrDefault("mapFilePath", "hatyai_map.graphml"));
        boolean ok = state.loadMap(path);
        respondState(ex, ok);
    }

    private void handleSetDepot(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = readJsonBody(ex);
        String nodeId = String.valueOf(body.get("nodeId"));
        boolean ok = state.setDepotById(nodeId);
        respondState(ex, ok);
    }

    private void handleSetCamp(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = readJsonBody(ex);
        String nodeId = String.valueOf(body.get("nodeId"));

        // อัปเดตพารามิเตอร์ demand ก่อนตั้งแคม ถ้าหน้าเว็บส่งมาด้วย (กรณีผู้ใช้ปรับฟอร์มก่อนคลิกเลือกจุด)
        if (body.containsKey("water")) {
            double water = toDouble(body.get("water"), 500.0);
            double medical = toDouble(body.get("medical"), 50.0);
            double startMin = toDouble(body.get("startMin"), 60.0);
            double endMin = toDouble(body.get("endMin"), 180.0);
            state.setDemandParams(water, medical, startMin, endMin);
        }

        boolean ok = state.setCampById(nodeId);
        respondState(ex, ok);
    }

    private void handleSimulateFlood(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = readJsonBody(ex);
        double lat = toDouble(body.get("lat"), 7.0100);
        double lon = toDouble(body.get("lon"), 100.4700);
        double radiusKm = toDouble(body.get("radiusKm"), 2.5);
        boolean ok = state.simulateFlood(lat, lon, radiusKm);
        respondState(ex, ok);
    }

    private void handleSetupFleet(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = readJsonBody(ex);
        int fleetSize = (int) toDouble(body.get("fleetSize"), 2.0);
        double capacity = toDouble(body.get("capacity"), 1000.0);
        boolean ok = state.setupFleet(fleetSize, capacity);
        respondState(ex, ok);
    }

    private void handleRunAco(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        Map<String, Object> body = readJsonBody(ex);
        double alpha = toDouble(body.get("alpha"), 0.6);
        double beta = toDouble(body.get("beta"), 0.4);
        int iterations = (int) toDouble(body.get("iterations"), 100.0);
        boolean ok = state.runAco(alpha, beta, iterations);
        respondState(ex, ok);
    }

    private void handleGetState(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        respondState(ex, true);
    }

    private void handleGetLogs(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;
        Map<String, Object> result = new LinkedHashMap<>();
        synchronized (this) {
            result.put("lines", new java.util.ArrayList<Object>(logBuffer));
        }
        sendJson(ex, 200, result);
    }

    private void handleReset(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;
        synchronized (this) {
            logBuffer.clear();
        }
        // สร้าง SimulationState ใหม่ทั้งหมด — วิธีที่ปลอดภัยที่สุดในการล้าง state ทุกตัวพร้อมกัน
        // (ตัวแปร state ไม่ใช่ final จึงสับเปลี่ยนได้ตรงนี้ ทุก handler หลังจากนี้จะใช้ตัวใหม่ทันที)
        this.state = new SimulationState(this::pushLog);
        pushLog("[🔄] รีเซ็ตการจำลองเรียบร้อย พร้อมเริ่มใหม่");
        respondState(ex, true);
    }

    // ==================================================================
    //  HELPERS
    // ==================================================================

    private boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase(method)) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return false;
        }
        // จัดการ CORS preflight แบบหยาบๆ เผื่อ frontend รันจาก origin อื่นในอนาคต
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        return true;
    }

    private Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) return new LinkedHashMap<>();
            return Json.parseObject(body);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private double toDouble(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void respondState(HttpExchange ex, boolean ok) throws IOException {
        Map<String, Object> result = state.toStateJson();
        result.put("ok", ok);
        sendJson(ex, 200, result);
    }

    private void sendJson(HttpExchange ex, int statusCode, Map<String, Object> payload) throws IOException {
        String json = Json.stringify(payload);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ==================================================================
    //  STATIC FILE SERVING (สำหรับหน้าเว็บ Leaflet)
    // ==================================================================

    private static class StaticFileHandler implements HttpHandler {
        private final Path root;

        StaticFileHandler(String webRoot) {
            this.root = Path.of(webRoot).toAbsolutePath().normalize();
        }

        boolean rootExists() {
            return Files.isDirectory(root);
        }

        String rootPath() {
            return root.toString();
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String requestPath = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
            if (requestPath.equals("/") || requestPath.isEmpty()) {
                requestPath = "/index.html";
            }

            Path filePath = root.resolve(requestPath.substring(1)).normalize();

            // ป้องกัน path traversal (เช่น ../../etc/passwd) ออกนอกโฟลเดอร์ webapp/
            if (!filePath.startsWith(root) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(notFound);
                }
                return;
            }

            String contentType = guessContentType(filePath.toString());
            byte[] content = Files.readAllBytes(filePath);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, content.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(content);
            }
        }

        private String guessContentType(String filename) {
            if (filename.endsWith(".html")) return "text/html; charset=UTF-8";
            if (filename.endsWith(".css")) return "text/css; charset=UTF-8";
            if (filename.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (filename.endsWith(".json")) return "application/json; charset=UTF-8";
            if (filename.endsWith(".graphml") || filename.endsWith(".xml")) return "application/xml; charset=UTF-8";
            if (filename.endsWith(".svg")) return "image/svg+xml";
            if (filename.endsWith(".png")) return "image/png";
            return "application/octet-stream";
        }
    }
}
