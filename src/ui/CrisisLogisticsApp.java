package src.ui;

import javax.swing.*;

/**
 * CrisisLogisticsApp
 * -------------------
 * Entry point ใหม่สำหรับรันโปรแกรมในโหมด Desktop GUI (Swing)
 *
 * วิธีรัน (หลัง compile แล้ว):
 *   java -cp "out:lib/*" src.ui.CrisisLogisticsApp
 *
 * (โหมด console เดิมยังใช้ได้ตามปกติผ่าน src.Main หากต้องการรันแบบ command-line)
 */
public class CrisisLogisticsApp {

    public static void main(String[] args) {
        // ใช้ Look & Feel ของระบบปฏิบัติการ (Windows/macOS/Linux) ให้หน้าตาโปรแกรมกลมกลืนขึ้น
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // ถ้าตั้งไม่ได้ก็ใช้ default ของ Swing ต่อไป ไม่ใช่ปัญหาคอขาด
        }

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
