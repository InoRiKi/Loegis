package src.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;

/**
 * MainWindow
 * ----------
 * หน้าต่างหลักของโปรแกรม Crisis Logistics Simulation
 *
 * Layout:
 *   [ ซ้าย ] แผงควบคุม — เลือกไฟล์แผนที่ / ปรับพารามิเตอร์ / ปุ่มรันทีละสเตป / log console
 *   [ ขวา  ] MapPanel  — ภาพแผนที่กราฟ พร้อม pan/zoom และ overlay เส้นทาง
 *
 * ทุกการรัน logic (โหลดไฟล์, ACO ฯลฯ) ทำผ่าน SwingWorker เพื่อไม่ให้ UI thread ค้าง
 * เพราะ ACO ที่รัน 100+ iterations บนกราฟใหญ่อาจกินเวลาหลายวินาที
 */
public class MainWindow extends JFrame {

    private final SimulationController controller;
    private final MapPanel mapPanel;
    private final JTextArea logArea;

    // --- ฟอร์มพารามิเตอร์ ---
    private final JTextField mapFileField;
    private final JTextField floodLatField;
    private final JTextField floodLonField;
    private final JTextField floodRadiusField;
    private final JTextField demandWaterField;
    private final JTextField demandMedicalField;
    private final JTextField timeWindowStartField;
    private final JTextField timeWindowEndField;
    private final JTextField fleetSizeField;
    private final JTextField vehicleCapacityField;
    private final JTextField acoAlphaField;
    private final JTextField acoBetaField;
    private final JTextField acoIterationsField;

    // --- ปุ่มสเตป ---
    private JButton btnStep1, btnStep2, btnStep3, btnStep4, btnStep5, btnStep6, btnRunAll, btnReset;

    private int currentStep = 0; // ใช้ track ว่าทำถึงสเตปไหนแล้ว เพื่อ enable/disable ปุ่มให้ถูกลำดับ

    public MainWindow() {
        super("Crisis Logistics Simulation — ระบบจำลองการจัดเส้นทางเสบียงภาวะวิกฤต");

        logArea = new JTextArea();
        controller = new SimulationController(this::appendLog);
        mapPanel = new MapPanel();

        mapFileField = new JTextField(controller.getMapFilePath());
        floodLatField = new JTextField(String.valueOf(controller.getFloodLatitude()));
        floodLonField = new JTextField(String.valueOf(controller.getFloodLongitude()));
        floodRadiusField = new JTextField(String.valueOf(controller.getFloodRadiusKm()));
        demandWaterField = new JTextField(String.valueOf(controller.getDemandWaterPacks()));
        demandMedicalField = new JTextField(String.valueOf(controller.getDemandMedicalKits()));
        timeWindowStartField = new JTextField(String.valueOf(controller.getTimeWindowStartMin()));
        timeWindowEndField = new JTextField(String.valueOf(controller.getTimeWindowEndMin()));
        fleetSizeField = new JTextField(String.valueOf(controller.getFleetSize()));
        vehicleCapacityField = new JTextField(String.valueOf(controller.getVehicleCapacityKg()));
        acoAlphaField = new JTextField(String.valueOf(controller.getAcoAlpha()));
        acoBetaField = new JTextField(String.valueOf(controller.getAcoBeta()));
        acoIterationsField = new JTextField(String.valueOf(controller.getAcoIterations()));

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 820);
        setMinimumSize(new Dimension(1000, 650));
        setLocationRelativeTo(null);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildControlPanel(), buildMapContainer());
        splitPane.setDividerLocation(380);
        splitPane.setOneTouchExpandable(true);

        setContentPane(splitPane);
        updateStepButtonStates();
    }

    // ==================================================================
    //  CONTROL PANEL (ฝั่งซ้าย)
    // ==================================================================

    private JComponent buildControlPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BorderLayout());
        root.setPreferredSize(new Dimension(380, 0));

        JPanel formWrapper = new JPanel();
        formWrapper.setLayout(new BoxLayout(formWrapper, BoxLayout.Y_AXIS));
        formWrapper.setBorder(new EmptyBorder(10, 10, 10, 10));

        formWrapper.add(buildMapFileSection());
        formWrapper.add(Box.createVerticalStrut(10));
        formWrapper.add(buildFloodSection());
        formWrapper.add(Box.createVerticalStrut(10));
        formWrapper.add(buildDemandSection());
        formWrapper.add(Box.createVerticalStrut(10));
        formWrapper.add(buildFleetSection());
        formWrapper.add(Box.createVerticalStrut(10));
        formWrapper.add(buildAcoSection());
        formWrapper.add(Box.createVerticalStrut(10));
        formWrapper.add(buildStepButtonsSection());

        JScrollPane formScroll = new JScrollPane(formWrapper);
        formScroll.setBorder(null);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new TitledBorder("📜 Log การทำงาน"));
        logScroll.setPreferredSize(new Dimension(380, 260));

        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, logScroll);
        verticalSplit.setResizeWeight(0.62);
        verticalSplit.setDividerLocation(420);

        root.add(verticalSplit, BorderLayout.CENTER);
        return root;
    }

    private JPanel sectionPanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        panel.setBorder(new TitledBorder(title));
        return panel;
    }

    private GridBagConstraints labelConstraints(int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 4, 3, 4);
        return gbc;
    }

    private GridBagConstraints fieldConstraints(int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 4, 3, 4);
        return gbc;
    }

    private JPanel buildMapFileSection() {
        JPanel panel = sectionPanel("🗺️ ไฟล์แผนที่ (.graphml)");

        JButton browseBtn = new JButton("เลือกไฟล์...");
        browseBtn.addActionListener(e -> browseForMapFile());

        GridBagConstraints gbcField = new GridBagConstraints();
        gbcField.gridx = 0;
        gbcField.gridy = 0;
        gbcField.fill = GridBagConstraints.HORIZONTAL;
        gbcField.weightx = 1.0;
        gbcField.insets = new Insets(3, 4, 3, 4);
        panel.add(mapFileField, gbcField);

        GridBagConstraints gbcBtn = new GridBagConstraints();
        gbcBtn.gridx = 1;
        gbcBtn.gridy = 0;
        gbcBtn.insets = new Insets(3, 4, 3, 4);
        panel.add(browseBtn, gbcBtn);

        return panel;
    }

    private JPanel buildFloodSection() {
        JPanel panel = sectionPanel("🌊 จุดน้ำท่วม (Disaster Zone)");
        addRow(panel, 0, "Latitude:", floodLatField);
        addRow(panel, 1, "Longitude:", floodLonField);
        addRow(panel, 2, "รัศมี (กม.):", floodRadiusField);
        return panel;
    }

    private JPanel buildDemandSection() {
        JPanel panel = sectionPanel("⛺ ความต้องการของค่ายผู้ประสบภัย");
        addRow(panel, 0, "น้ำ (แพ็ค):", demandWaterField);
        addRow(panel, 1, "เวชภัณฑ์ (ชุด):", demandMedicalField);
        addRow(panel, 2, "เวลาเริ่ม (นาที):", timeWindowStartField);
        addRow(panel, 3, "เวลาสิ้นสุด (นาที):", timeWindowEndField);
        return panel;
    }

    private JPanel buildFleetSection() {
        JPanel panel = sectionPanel("🚚 กองรถกู้ภัย (Fleet)");
        addRow(panel, 0, "จำนวนรถ:", fleetSizeField);
        addRow(panel, 1, "ความสามารถบรรทุก (กก.):", vehicleCapacityField);
        return panel;
    }

    private JPanel buildAcoSection() {
        JPanel panel = sectionPanel("🤖 พารามิเตอร์ Ant Colony Optimization");
        addRow(panel, 0, "Alpha (น้ำหนักความเร็ว):", acoAlphaField);
        addRow(panel, 1, "Beta (น้ำหนักความปลอดภัย):", acoBetaField);
        addRow(panel, 2, "จำนวนรอบ (iterations):", acoIterationsField);
        return panel;
    }

    private void addRow(JPanel panel, int row, String label, JTextField field) {
        panel.add(new JLabel(label), labelConstraints(row));
        panel.add(field, fieldConstraints(row));
    }

    private JPanel buildStepButtonsSection() {
        JPanel panel = sectionPanel("▶️ ขั้นตอนการจำลอง (Step-by-step)");
        panel.setLayout(new GridLayout(0, 1, 4, 4));

        btnStep1 = new JButton("1️⃣ โหลดแผนที่");
        btnStep2 = new JButton("2️⃣ กำหนด Depot / ค่ายผู้ประสบภัย");
        btnStep3 = new JButton("3️⃣ จำลองน้ำท่วม");
        btnStep4 = new JButton("4️⃣ จัดเตรียมกองรถ");
        btnStep5 = new JButton("5️⃣ หาเส้นทาง (ACO)");
        btnStep6 = new JButton("6️⃣ ประเมินผล + สรุปรายงาน");
        btnRunAll = new JButton("⏩ รันทั้งหมดอัตโนมัติ");
        btnReset = new JButton("🔄 รีเซ็ตการจำลอง");

        btnStep1.addActionListener(e -> runStep1());
        btnStep2.addActionListener(e -> runStep2());
        btnStep3.addActionListener(e -> runStep3());
        btnStep4.addActionListener(e -> runStep4());
        btnStep5.addActionListener(e -> runStep5());
        btnStep6.addActionListener(e -> runStep6AndReport());
        btnRunAll.addActionListener(e -> runAll());
        btnReset.addActionListener(e -> resetSimulation());

        panel.add(btnStep1);
        panel.add(btnStep2);
        panel.add(btnStep3);
        panel.add(btnStep4);
        panel.add(btnStep5);
        panel.add(btnStep6);
        panel.add(Box.createVerticalStrut(6));
        panel.add(btnRunAll);
        panel.add(btnReset);

        return panel;
    }

    // ==================================================================
    //  MAP CONTAINER (ฝั่งขวา)
    // ==================================================================

    private JComponent buildMapContainer() {
        JPanel container = new JPanel(new BorderLayout());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton resetViewBtn = new JButton("🎯 จัดกึ่งกลางแผนที่");
        resetViewBtn.addActionListener(e -> mapPanel.resetView());
        JLabel hint = new JLabel("  ลากเมาส์ = เลื่อนแผนที่ | scroll = ซูม");
        hint.setForeground(Color.GRAY);
        toolbar.add(resetViewBtn);
        toolbar.add(hint);

        container.add(toolbar, BorderLayout.NORTH);
        container.add(mapPanel, BorderLayout.CENTER);
        return container;
    }

    // ==================================================================
    //  ACTION HANDLERS — อ่านค่าจากฟอร์ม, sync เข้า controller, รันผ่าน SwingWorker
    // ==================================================================

    private void browseForMapFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("GraphML files (*.graphml)", "graphml"));
        File currentFile = new File(mapFileField.getText().trim());
        if (currentFile.getParentFile() != null && currentFile.getParentFile().exists()) {
            chooser.setCurrentDirectory(currentFile.getParentFile());
        }
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            mapFileField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /** อ่านค่าทุกฟิลด์จากฟอร์ม -> sync เข้า controller ก่อนรันทุกครั้ง กันกรณีผู้ใช้แก้ค่าระหว่างทาง */
    private boolean syncParametersFromForm() {
        try {
            controller.setMapFilePath(mapFileField.getText().trim());
            controller.setFloodLatitude(parseDouble(floodLatField));
            controller.setFloodLongitude(parseDouble(floodLonField));
            controller.setFloodRadiusKm(parseDouble(floodRadiusField));
            controller.setDemandWaterPacks(parseDouble(demandWaterField));
            controller.setDemandMedicalKits(parseDouble(demandMedicalField));
            controller.setTimeWindowStartMin(parseDouble(timeWindowStartField));
            controller.setTimeWindowEndMin(parseDouble(timeWindowEndField));
            controller.setFleetSize((int) parseDouble(fleetSizeField));
            controller.setVehicleCapacityKg(parseDouble(vehicleCapacityField));
            controller.setAcoAlpha(parseDouble(acoAlphaField));
            controller.setAcoBeta(parseDouble(acoBetaField));
            controller.setAcoIterations((int) parseDouble(acoIterationsField));
            return true;
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this,
                    "กรุณากรอกตัวเลขให้ถูกต้องในทุกฟิลด์พารามิเตอร์\n(" + ex.getMessage() + ")",
                    "ค่าพารามิเตอร์ไม่ถูกต้อง", JOptionPane.WARNING_MESSAGE);
            return false;
        }
    }

    private double parseDouble(JTextField field) {
        return Double.parseDouble(field.getText().trim());
    }

    private void runStep1() {
        if (!syncParametersFromForm()) return;
        runInBackground(controller::step1_loadMap, success -> {
            if (success) {
                mapPanel.setGraph(controller.getGraph());
                currentStep = 1;
            }
            updateStepButtonStates();
        });
    }

    private void runStep2() {
        runInBackground(controller::step2_setupDepotAndCamp, success -> {
            if (success) {
                mapPanel.setDepot(controller.getDepot());
                mapPanel.setRefugeeCamp(controller.getRefugeeCamp());
                currentStep = 2;
            }
            updateStepButtonStates();
        });
    }

    private void runStep3() {
        runInBackground(controller::step3_simulateFlood, success -> {
            if (success) {
                mapPanel.repaint(); // riskLevel ของ edge ถูกแก้ใน object เดิม แค่ repaint ก็พอ
                currentStep = 3;
            }
            updateStepButtonStates();
        });
    }

    private void runStep4() {
        runInBackground(controller::step4_setupFleet, success -> {
            if (success) {
                currentStep = 4;
            }
            updateStepButtonStates();
        });
    }

    private void runStep5() {
        runInBackground(controller::step5_runRouteOptimization, success -> {
            if (success) {
                mapPanel.setOptimalRoute(controller.getOptimalRoute());
                currentStep = 5;
            }
            updateStepButtonStates();
        });
    }

    private void runStep6AndReport() {
        runInBackground(() -> {
            boolean ok = controller.step6_evaluateMockRoute();
            controller.step7_printFinalReport();
            return ok;
        }, success -> {
            currentStep = 6;
            updateStepButtonStates();
        });
    }

    private void runAll() {
        if (!syncParametersFromForm()) return;
        setAllButtonsEnabled(false);
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                controller.runAllSteps();
                return null;
            }

            @Override
            protected void done() {
                mapPanel.setGraph(controller.getGraph());
                mapPanel.setDepot(controller.getDepot());
                mapPanel.setRefugeeCamp(controller.getRefugeeCamp());
                mapPanel.setOptimalRoute(controller.getOptimalRoute());
                currentStep = controller.isGraphLoaded() ? 6 : 0;
                updateStepButtonStates();
            }
        };
        worker.execute();
    }

    private void resetSimulation() {
        currentStep = 0;
        logArea.setText("");
        mapPanel.setGraph(null);
        mapPanel.setOptimalRoute(null);
        updateStepButtonStates();
        appendLog("[🔄] รีเซ็ตการจำลองเรียบร้อย พร้อมเริ่มใหม่");
    }

    /**
     * รัน supplier (logic ที่อาจใช้เวลานาน) บน background thread แล้วเรียก callback บน EDT เมื่อเสร็จ
     * ปิดปุ่มทั้งหมดระหว่างรัน เพื่อกันผู้ใช้กดซ้ำหรือกดสเตปอื่นแซงคิว
     */
    private void runInBackground(java.util.function.BooleanSupplier task, java.util.function.Consumer<Boolean> onDone) {
        setAllButtonsEnabled(false);
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                return task.getAsBoolean();
            }

            @Override
            protected void done() {
                boolean result;
                try {
                    result = get();
                } catch (Exception ex) {
                    appendLog("[❌ Error] เกิดข้อผิดพลาดขณะรัน: " + ex.getMessage());
                    result = false;
                }
                onDone.accept(result);
            }
        };
        worker.execute();
    }

    /** enable/disable ปุ่มสเตปตามลำดับที่ทำได้จริง ป้องกันกดสเตปข้ามลำดับ */
    private void updateStepButtonStates() {
        btnStep1.setEnabled(true);
        btnStep2.setEnabled(currentStep >= 1);
        btnStep3.setEnabled(currentStep >= 2);
        btnStep4.setEnabled(currentStep >= 2);
        btnStep5.setEnabled(currentStep >= 4);
        btnStep6.setEnabled(currentStep >= 5);
        btnRunAll.setEnabled(true);
        btnReset.setEnabled(true);
    }

    private void setAllButtonsEnabled(boolean enabled) {
        btnStep1.setEnabled(enabled);
        btnStep2.setEnabled(enabled);
        btnStep3.setEnabled(enabled);
        btnStep4.setEnabled(enabled);
        btnStep5.setEnabled(enabled);
        btnStep6.setEnabled(enabled);
        btnRunAll.setEnabled(enabled);
        btnReset.setEnabled(enabled);
    }

    /** เรียกจาก background thread ได้อย่างปลอดภัย — จะ marshal เข้า EDT ให้เองผ่าน SwingUtilities */
    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
