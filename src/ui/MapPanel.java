package src.ui;

import src.model.CrisisGraph;
import src.model.Edge;
import src.model.Node;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MapPanel
 * --------
 * วาดกราฟแผนที่วิกฤต (CrisisGraph) เป็นภาพ 2D บน Swing โดยแปลงพิกัด (lat, lon) -> พิกัดหน้าจอ (x, y)
 *
 * สีที่ใช้:
 *   - เส้นถนนปกติ        : เทาอ่อน
 *   - เส้นถนนเสี่ยง/น้ำท่วม : แดง/ส้ม ตาม riskLevel (ยิ่งเข้ม ยิ่งเสี่ยงสูง)
 *   - เส้นทางที่ ACO เลือก : น้ำเงินสด หนาเป็นพิเศษ วาดทับบนสุด
 *   - โหนดคลังเสบียง (depot) : สี่เหลี่ยมเขียว
 *   - โหนดค่ายผู้ประสบภัย (demand) : วงกลมแดงใหญ่
 *   - โหนดทั่วไป         : จุดเทาเล็กๆ
 *
 * รองรับ pan (ลากเมาส์ซ้าย) และ zoom (scroll wheel)
 */
public class MapPanel extends JPanel {

    private CrisisGraph graph;
    private Node depot;
    private Node refugeeCamp;
    private List<Edge> optimalRoute;

    // ขอบเขตพิกัดจริงของกราฟ (ใช้ normalize ก่อนวาด)
    private double minLat, maxLat, minLon, maxLon;
    private boolean boundsValid = false;

    // กล้อง (pan/zoom) ของผู้ใช้
    private double zoom = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;
    private Point lastDragPoint;

    private static final Color COLOR_BG = new Color(245, 247, 250);
    private static final Color COLOR_EDGE_NORMAL = new Color(190, 196, 204);
    private static final Color COLOR_NODE_NORMAL = new Color(140, 148, 158);
    private static final Color COLOR_DEPOT = new Color(34, 153, 84);
    private static final Color COLOR_DEMAND = new Color(214, 48, 49);
    private static final Color COLOR_ROUTE = new Color(33, 99, 235);
    private static final Color COLOR_RISK_LOW = new Color(255, 193, 84);
    private static final Color COLOR_RISK_HIGH = new Color(192, 28, 28);

    public MapPanel() {
        setBackground(COLOR_BG);
        setupMouseControls();
    }

    private void setupMouseControls() {
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastDragPoint = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragPoint != null) {
                    panX += (e.getX() - lastDragPoint.x);
                    panY += (e.getY() - lastDragPoint.y);
                    lastDragPoint = e.getPoint();
                    repaint();
                }
            }
        };
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);

        addMouseWheelListener(e -> {
            double oldZoom = zoom;
            double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 0.9;
            zoom = Math.max(0.05, Math.min(50.0, zoom * factor));

            // ซูมเข้า-ออกโดยยึดตำแหน่งเมาส์เป็นจุดศูนย์กลาง (ไม่กระโดด)
            double scaleChange = zoom / oldZoom;
            panX = e.getX() - (e.getX() - panX) * scaleChange;
            panY = e.getY() - (e.getY() - panY) * scaleChange;

            repaint();
        });

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!boundsValid) {
                    resetView();
                }
            }
        });
    }

    /** เรียกเมื่อโหลด/รีเฟรชกราฟใหม่ — คำนวณขอบเขตพิกัดและรีเซ็ตกล้อง */
    public void setGraph(CrisisGraph graph) {
        this.graph = graph;
        computeBounds();
        resetView();
        repaint();
    }

    public void setDepot(Node depot) {
        this.depot = depot;
        repaint();
    }

    public void setRefugeeCamp(Node refugeeCamp) {
        this.refugeeCamp = refugeeCamp;
        repaint();
    }

    public void setOptimalRoute(List<Edge> route) {
        this.optimalRoute = route;
        repaint();
    }

    public void clearRoute() {
        this.optimalRoute = null;
        repaint();
    }

    private void computeBounds() {
        if (graph == null || graph.getAllNodes().isEmpty()) {
            boundsValid = false;
            return;
        }

        minLat = Double.MAX_VALUE;
        maxLat = -Double.MAX_VALUE;
        minLon = Double.MAX_VALUE;
        maxLon = -Double.MAX_VALUE;

        for (Node n : graph.getAllNodes().values()) {
            minLat = Math.min(minLat, n.getLatitude());
            maxLat = Math.max(maxLat, n.getLatitude());
            minLon = Math.min(minLon, n.getLongitude());
            maxLon = Math.max(maxLon, n.getLongitude());
        }

        // กันกรณีกราฟมีจุดเดียวหรือทุกจุดพิกัดเท่ากัน (หารด้วยศูนย์)
        if (maxLat - minLat < 1e-9) { maxLat += 0.0005; minLat -= 0.0005; }
        if (maxLon - minLon < 1e-9) { maxLon += 0.0005; minLon -= 0.0005; }

        boundsValid = true;
    }

    /** จัดกึ่งกลาง + สเกลกราฟให้พอดีกับขนาดหน้าต่างปัจจุบัน */
    public void resetView() {
        zoom = 1.0;
        panX = 0.0;
        panY = 0.0;

        if (!boundsValid || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        double margin = 40;
        double worldW = maxLon - minLon;
        double worldH = maxLat - minLat;

        double scaleX = (getWidth() - 2 * margin) / worldW;
        // lat/lon ไม่ใช่สเกลเดียวกันเป๊ะ แต่สำหรับพื้นที่เล็กระดับเมือง ถือว่าใกล้เคียงพอ
        double scaleY = (getHeight() - 2 * margin) / worldH;
        double baseScale = Math.min(scaleX, scaleY);

        this.zoom = baseScale;

        // จัดให้อยู่กึ่งกลางพาเนล
        double worldCenterX = (minLon + maxLon) / 2.0;
        double worldCenterY = (minLat + maxLat) / 2.0;
        Point2D screenCenter = projectRaw(worldCenterY, worldCenterX, baseScale, 0, 0);

        panX = getWidth() / 2.0 - screenCenter.getX();
        panY = getHeight() / 2.0 - screenCenter.getY();

        repaint();
    }

    /** แปลง (lat, lon) -> พิกัดหน้าจอ โดยยังไม่บวก pan ปัจจุบัน (ใช้ภายในสำหรับ resetView) */
    private Point2D projectRaw(double lat, double lon, double scale, double px, double py) {
        double x = (lon - minLon) * scale + px;
        // lat แกน y ของจอกลับด้าน (lat สูง = ทิศเหนือ = ด้านบนจอ)
        double y = (maxLat - lat) * scale + py;
        return new Point2D.Double(x, y);
    }

    private Point2D project(double lat, double lon) {
        return projectRaw(lat, lon, zoom, panX, panY);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        if (graph == null || !boundsValid) {
            drawEmptyState(g2);
            return;
        }

        drawEdges(g2);
        drawRoute(g2);
        drawNodes(g2);
        drawLegend(g2);
    }

    private void drawEmptyState(Graphics2D g2) {
        g2.setColor(COLOR_NODE_NORMAL);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
        String msg = "ยังไม่มีข้อมูลแผนที่ — กรุณาโหลดไฟล์ .graphml จากแผงควบคุมด้านซ้าย";
        FontMetrics fm = g2.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(msg)) / 2;
        int y = getHeight() / 2;
        g2.drawString(msg, Math.max(10, x), y);
    }

    private void drawEdges(Graphics2D g2) {
        for (Edge edge : graph.getAllEdges().values()) {
            Node s = edge.getSource();
            Node t = edge.getTarget();
            if (s == null || t == null) continue;

            Point2D p1 = project(s.getLatitude(), s.getLongitude());
            Point2D p2 = project(t.getLatitude(), t.getLongitude());

            double risk = edge.getRiskLevel();
            if (risk > 0.0) {
                g2.setColor(blendRiskColor(risk));
                g2.setStroke(new BasicStroke(2.0f));
            } else {
                g2.setColor(COLOR_EDGE_NORMAL);
                g2.setStroke(new BasicStroke(1.0f));
            }

            g2.draw(new Line2D.Double(p1, p2));
        }
    }

    private Color blendRiskColor(double risk) {
        double t = Math.max(0.0, Math.min(1.0, risk));
        int r = (int) (COLOR_RISK_LOW.getRed() + t * (COLOR_RISK_HIGH.getRed() - COLOR_RISK_LOW.getRed()));
        int g = (int) (COLOR_RISK_LOW.getGreen() + t * (COLOR_RISK_HIGH.getGreen() - COLOR_RISK_LOW.getGreen()));
        int b = (int) (COLOR_RISK_LOW.getBlue() + t * (COLOR_RISK_HIGH.getBlue() - COLOR_RISK_LOW.getBlue()));
        return new Color(r, g, b);
    }

    private void drawRoute(Graphics2D g2) {
        if (optimalRoute == null || optimalRoute.isEmpty()) return;

        g2.setColor(COLOR_ROUTE);
        g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (Edge edge : optimalRoute) {
            Node s = edge.getSource();
            Node t = edge.getTarget();
            if (s == null || t == null) continue;
            Point2D p1 = project(s.getLatitude(), s.getLongitude());
            Point2D p2 = project(t.getLatitude(), t.getLongitude());
            g2.draw(new Line2D.Double(p1, p2));
        }

        // วาดลูกศรบอกทิศทางเป็นระยะ เพื่อให้รู้ว่ารถวิ่งไปทางไหน
        drawDirectionArrows(g2);
    }

    private void drawDirectionArrows(Graphics2D g2) {
        int step = Math.max(1, optimalRoute.size() / 8); // ไม่ต้องวาดทุกเส้น เดี๋ยวรก
        for (int i = 0; i < optimalRoute.size(); i += step) {
            Edge edge = optimalRoute.get(i);
            Node s = edge.getSource();
            Node t = edge.getTarget();
            if (s == null || t == null) continue;

            Point2D p1 = project(s.getLatitude(), s.getLongitude());
            Point2D p2 = project(t.getLatitude(), t.getLongitude());
            drawArrowHead(g2, p1, p2);
        }
    }

    private void drawArrowHead(Graphics2D g2, Point2D from, Point2D to) {
        double angle = Math.atan2(to.getY() - from.getY(), to.getX() - from.getX());
        double midX = (from.getX() + to.getX()) / 2.0;
        double midY = (from.getY() + to.getY()) / 2.0;

        double arrowLen = 9;
        double arrowAngle = Math.toRadians(28);

        drawArrowTriangle(g2, midX, midY, angle, arrowLen, arrowAngle);
    }

    private void drawArrowTriangle(Graphics2D g2, double x, double y, double angle, double len, double spread) {
        double x1 = x - len * Math.cos(angle - spread);
        double y1 = y - len * Math.sin(angle - spread);
        double x2 = x - len * Math.cos(angle + spread);
        double y2 = y - len * Math.sin(angle + spread);

        Polygon arrow = new Polygon();
        arrow.addPoint((int) x, (int) y);
        arrow.addPoint((int) x1, (int) y1);
        arrow.addPoint((int) x2, (int) y2);
        g2.fillPolygon(arrow);
    }

    private void drawNodes(Graphics2D g2) {
        Set<String> specialNodeIds = new HashSet<>();
        if (depot != null) specialNodeIds.add(depot.getId());
        if (refugeeCamp != null) specialNodeIds.add(refugeeCamp.getId());

        // วาดโหนดทั่วไปก่อน (เล็ก เบา) แล้วค่อยวาด depot/camp ทับด้านบนให้เด่น
        for (Node n : graph.getAllNodes().values()) {
            if (specialNodeIds.contains(n.getId())) continue;
            Point2D p = project(n.getLatitude(), n.getLongitude());
            g2.setColor(COLOR_NODE_NORMAL);
            double r = 2.2;
            g2.fill(new java.awt.geom.Ellipse2D.Double(p.getX() - r, p.getY() - r, r * 2, r * 2));
        }

        if (depot != null) {
            drawSquareMarker(g2, depot, COLOR_DEPOT, "🏢 Depot");
        }
        if (refugeeCamp != null) {
            drawCircleMarker(g2, refugeeCamp, COLOR_DEMAND, "⛺ Camp");
        }
    }

    private void drawSquareMarker(Graphics2D g2, Node n, Color color, String label) {
        Point2D p = project(n.getLatitude(), n.getLongitude());
        double r = 8;
        g2.setColor(color);
        g2.fillRect((int) (p.getX() - r), (int) (p.getY() - r), (int) (r * 2), (int) (r * 2));
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRect((int) (p.getX() - r), (int) (p.getY() - r), (int) (r * 2), (int) (r * 2));
        drawLabel(g2, p, label, color);
    }

    private void drawCircleMarker(Graphics2D g2, Node n, Color color, String label) {
        Point2D p = project(n.getLatitude(), n.getLongitude());
        double r = 8;
        g2.setColor(color);
        g2.fill(new java.awt.geom.Ellipse2D.Double(p.getX() - r, p.getY() - r, r * 2, r * 2));
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new java.awt.geom.Ellipse2D.Double(p.getX() - r, p.getY() - r, r * 2, r * 2));
        drawLabel(g2, p, label, color);
    }

    private void drawLabel(Graphics2D g2, Point2D p, String text, Color color) {
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(color.darker());
        g2.drawString(text, (float) (p.getX() + 11), (float) (p.getY() - 8));
    }

    private void drawLegend(Graphics2D g2) {
        int x = 12, y = 12, lineH = 18;
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));

        legendItem(g2, x, y, COLOR_DEPOT, "คลังเสบียง (Depot)");
        legendItem(g2, x, y + lineH, COLOR_DEMAND, "ค่ายผู้ประสบภัย (Demand)");
        legendItem(g2, x, y + lineH * 2, COLOR_ROUTE, "เส้นทางที่ ACO เลือก");
        legendItem(g2, x, y + lineH * 3, COLOR_RISK_HIGH, "ถนนเสี่ยง/น้ำท่วม");
        legendItem(g2, x, y + lineH * 4, COLOR_EDGE_NORMAL, "ถนนปกติ");
    }

    private void legendItem(Graphics2D g2, int x, int y, Color color, String text) {
        g2.setColor(color);
        g2.fillRect(x, y, 12, 12);
        g2.setColor(Color.DARK_GRAY);
        g2.drawString(text, x + 18, y + 11);
    }
}
