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
 * เซิร์ฟเวอร์ HTTP ขนาดเล็กสำหรับเชื่อมหน้าเว็บกับ logic Java
 *
 * หน้าที่:
 *   1. เสิร์ฟไฟล์หน้าเว็บจาก webapp/
 *   2. รับคำสั่งจากหน้าเว็บ เช่น โหลดแผนที่ ตั้ง Depot ตั้ง Camp
 *   3. รับจุดน้ำท่วมหลายจุดจากการคลิกบนแผนที่
 *   4. ส่งสถานะล่าสุดกลับไปให้หน้าเว็บวาดใหม่
 *
 * API ที่เพิ่มใหม่:
 *   - /api/addFloodZone
 *   - /api/clearFloodZones
 */
public class ApiServer {

    private SimulationState state;
    private final Deque<String> logBuffer = new ArrayDeque<>();
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
        server.createContext("/api/runRescueMission", this::handleRunRescueMission);
        server.createContext("/api/addRescuePoint", this::handleAddRescuePoint);
        server.createContext("/api/setTrafficMode", this::handleSetTrafficMode);
        server.createContext("/api/load", this::handleLoad);
        server.createContext("/api/setDepot", this::handleSetDepot);
        server.createContext("/api/setCamp", this::handleSetCamp);
        server.createContext("/api/simulateFlood", this::handleSimulateFlood);
        server.createContext("/api/addFloodZone", this::handleAddFloodZone);
        server.createContext("/api/clearFloodZones", this::handleClearFloodZones);
        server.createContext("/api/setupFleet", this::handleSetupFleet);
        server.createContext("/api/runRoute", this::handleRunRoute);
        server.createContext("/api/runAco", this::handleRunAco);
        server.createContext("/api/state", this::handleGetState);
        server.createContext("/api/logs", this::handleGetLogs);
        server.createContext("/api/reset", this::handleReset);

        StaticFileHandler staticHandler = new StaticFileHandler(webRoot);
        server.createContext("/", staticHandler);

        server.start();

        if (!staticHandler.rootExists()) {
            System.out.println("[⚠️ Warning] ไม่พบโฟลเดอร์ webapp/ ที่ path: "
                    + staticHandler.rootPath());
        }

        System.out.println("[🌐 Server] เปิดให้บริการที่ http://localhost:"
                + port
                + "  (กด Ctrl+C เพื่อปิด)");
    }

    private void handleRunRescueMission(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        boolean ok = state.runRescueMission();

        respondState(ex, ok);
    }

    private void handleAddRescuePoint(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);
        String nodeId = String.valueOf(body.get("nodeId"));

        boolean ok = state.addRescuePointById(nodeId);

        respondState(ex, ok);
    }

    private void handleRunRoute(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        double alpha = toDouble(body.get("alpha"), 0.6);
        double beta = toDouble(body.get("beta"), 0.4);

        boolean ok = state.runAco(alpha, beta, 100);

        respondState(ex, ok);
    }

    // ==================================================================
    //  API: โหลดแผนที่
    // ==================================================================

    private void handleLoad(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        String path = String.valueOf(body.getOrDefault("mapFilePath", "hatyai_map.graphml"));
        boolean respectOneWay = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("respectOneWay", "true"))
        );

        boolean ok = state.loadMap(path, respectOneWay);

        respondState(ex, ok);
    }

    private void handleSetTrafficMode(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        boolean respectOneWay = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("respectOneWay", "true"))
        );

        boolean ok = state.setTrafficMode(respectOneWay);

        respondState(ex, ok);
    }

    // ==================================================================
    //  API: ตั้ง Depot
    // ==================================================================

    private void handleSetDepot(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);
        String nodeId = String.valueOf(body.get("nodeId"));

        boolean ok = state.setDepotById(nodeId);

        respondState(ex, ok);
    }

    // ==================================================================
    //  API: ตั้ง Camp
    // ==================================================================

    private void handleSetCamp(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);
        String nodeId = String.valueOf(body.get("nodeId"));

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

    // ==================================================================
    //  API เดิม: จำลองน้ำท่วม 1 จุด
    // ==================================================================

    /**
     * API เดิมยังเก็บไว้
     *
     * ถ้าหน้าเว็บเก่ายังเรียก /api/simulateFlood อยู่
     * ระบบจะเพิ่มจุดน้ำท่วมระดับ MEDIUM ให้โดยอัตโนมัติ
     */
    private void handleSimulateFlood(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        double lat = toDouble(body.get("lat"), 7.0100);
        double lon = toDouble(body.get("lon"), 100.4700);
        double radiusKm = toDouble(body.get("radiusKm"), 2.5);
        String level = String.valueOf(body.getOrDefault("level", "MEDIUM"));

        boolean ok = state.addFloodZone(lat, lon, radiusKm, level);

        respondState(ex, ok);
    }

    // ==================================================================
    //  API ใหม่: เพิ่มจุดน้ำท่วมหลายจุด
    // ==================================================================

    /**
     * รับจุดน้ำท่วมจากหน้าเว็บ
     *
     * JSON ที่ส่งมา:
     * {
     *   "lat": 7.0100,
     *   "lon": 100.4700,
     *   "radiusKm": 1.0,
     *   "level": "DEEP"
     * }
     */
    private void handleAddFloodZone(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        double lat = toDouble(body.get("lat"), 7.0100);
        double lon = toDouble(body.get("lon"), 100.4700);
        double radiusKm = toDouble(body.get("radiusKm"), 1.0);
        String level = String.valueOf(body.getOrDefault("level", "MEDIUM"));

        boolean ok = state.addFloodZone(lat, lon, radiusKm, level);

        respondState(ex, ok);
    }

    // ==================================================================
    //  API ใหม่: ล้างจุดน้ำท่วมทั้งหมด
    // ==================================================================

    private void handleClearFloodZones(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        boolean ok = state.clearFloodZones();

        respondState(ex, ok);
    }

    // ==================================================================
    //  API: ตั้งค่ารถ
    // ==================================================================

    private void handleSetupFleet(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        int fleetSize = (int) toDouble(body.get("fleetSize"), 2.0);
        double capacity = toDouble(body.get("capacity"), 1000.0);

        boolean ok = state.setupFleet(fleetSize, capacity);

        respondState(ex, ok);
    }

    // ==================================================================
    //  API: รัน ACO
    // ==================================================================

    private void handleRunAco(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        Map<String, Object> body = readJsonBody(ex);

        double alpha = toDouble(body.get("alpha"), 0.6);
        double beta = toDouble(body.get("beta"), 0.4);
        int iterations = (int) toDouble(body.get("iterations"), 100.0);

        boolean ok = state.runRescueMission();

        respondState(ex, ok);
    }

    // ==================================================================
    //  API: ขอ state ล่าสุด
    // ==================================================================

    private void handleGetState(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;

        respondState(ex, true);
    }

    // ==================================================================
    //  API: ขอ log
    // ==================================================================

    private void handleGetLogs(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "GET")) return;

        Map<String, Object> result = new LinkedHashMap<>();

        synchronized (this) {
            result.put("lines", new java.util.ArrayList<Object>(logBuffer));
        }

        sendJson(ex, 200, result);
    }

    // ==================================================================
    //  API: รีเซ็ตระบบ
    // ==================================================================

    private void handleReset(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) return;

        synchronized (this) {
            logBuffer.clear();
        }

        this.state = new SimulationState(this::pushLog);

        pushLog("[🔄] รีเซ็ตการจำลองเรียบร้อย พร้อมเริ่มใหม่");

        respondState(ex, true);
    }

    // ==================================================================
    //  Helper
    // ==================================================================

    private boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase(method)) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return false;
        }

        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        return true;
    }

    private Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8).trim();

            if (body.isEmpty()) {
                return new LinkedHashMap<>();
            }

            return Json.parseObject(body);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private double toDouble(Object value, double fallback) {
        if (value == null) return fallback;

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

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
    //  Static File Handler
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
            String requestPath = URLDecoder.decode(
                    ex.getRequestURI().getPath(),
                    StandardCharsets.UTF_8
            );

            if (requestPath.equals("/") || requestPath.isEmpty()) {
                requestPath = "/index.html";
            }

            Path filePath = root.resolve(requestPath.substring(1)).normalize();

            if (!filePath.startsWith(root)
                    || !Files.exists(filePath)
                    || Files.isDirectory(filePath)) {

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