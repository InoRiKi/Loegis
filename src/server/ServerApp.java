package src.server;

/**
 * ServerApp
 * ----------
 * Entry point สำหรับรันโปรแกรมในโหมด Web App
 *
 * เปิดเซิร์ฟเวอร์ขึ้นมาที่ port 8080 (หรือกำหนดเองผ่าน argument แรก) แล้วเสิร์ฟหน้าเว็บจากโฟลเดอร์ webapp/
 * ผู้ใช้เปิดเบราว์เซอร์ไปที่ http://localhost:8080 เพื่อใช้งาน
 */
public class ServerApp {

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                // ใช้ port default ถ้า argument ไม่ใช่เลข
            }
        }

        String webRoot = resolveWebRoot();

        ApiServer server = new ApiServer();
        server.start(port, webRoot);
    }

    /**
     * หา path ของโฟลเดอร์ webapp/ แบบ robust — กันปัญหาเซิร์ฟเวอร์หาไฟล์หน้าเว็บไม่เจอ
     * เพราะผู้ใช้รันคำสั่ง java จาก working directory ที่ต่างกัน (เช่น รันจาก target/ vs รันจาก loegis/)
     */
    private static String resolveWebRoot() {
        String[] candidates = { "webapp", "../webapp", "./webapp" };
        for (String candidate : candidates) {
            if (new java.io.File(candidate, "index.html").exists()) {
                return candidate;
            }
        }
        // หาไม่เจอเลย — ใช้ค่า default แล้วให้ ApiServer แจ้ง error ตอนรันจริงไปเลย ดีกว่าเดาผิดแบบเงียบๆ
        return "webapp";
    }
}
