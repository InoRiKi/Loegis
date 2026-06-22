import tkinter as tk
import customtkinter as ctk
from tkintermapview import TkinterMapView
import subprocess
import json
import os

# ตั้งค่าธีมโปรแกรมให้ดูดุดัน ล้ำสมัยสไตล์โปรเจกต์กู้ภัยภัยพิบัติ
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

class LoegisUI(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("🚀 Loegis - ระบบจำลองการจัดเส้นทางเสบียงภาวะวิกฤต (Crisis Logistics)")
        self.geometry("1280x720")

        # --- การจัดการเลย์เอาต์หลัก (Sidebar & Map) ---
        self.grid_columnconfigure(0, weight=1)  # แผงควบคุมด้านซ้าย (Sidebar)
        self.grid_columnconfigure(1, weight=4)  # แผนที่ด้านขวา (Map Canvas)
        self.grid_rowconfigure(0, weight=1)

        # ================= แผงควบคุม (Sidebar Panel) =================
        self.sidebar = ctk.CTkFrame(self, width=300, corner_radius=0)
        self.sidebar.grid(row=0, column=0, sticky="nsew", padx=10, pady=10)
        
        self.title_label = ctk.CTkLabel(self.sidebar, text="LOEGIS CONTROL", font=ctk.CTkFont(size=20, weight="bold"))
        self.title_label.pack(pady=20, padx=10)

        # ปุ่มอัปโหลดแผนที่ GraphML
        self.btn_upload = ctk.CTkButton(self.sidebar, text="📁 อัปโหลดไฟล์แผนที่ (.graphml)", command=self.upload_map)
        self.btn_upload.pack(pady=10, padx=20, fill="x")

        self.map_status = ctk.CTkLabel(self.sidebar, text="แผนที่ปัจจุบัน: hatyai_map.graphml", text_color="green")
        self.map_status.pack(pady=5)

        # ---- ส่วนปรับพารามิเตอร์ฝูงมด ACO ----
        self.param_frame = ctk.CTkLabel(self.sidebar, text="⚙️ พารามิเตอร์ฝูงมด AI (ACO)", font=ctk.CTkFont(weight="bold"))
        self.param_frame.pack(pady=10)

        # Alpha Slider (ค่าน้ำหนักฟีโรโมน / สถิติเส้นทางเดิม)
        self.alpha_label = ctk.CTkLabel(self.sidebar, text="Alpha (เน้นความเร็วเส้นทาง): 0.60")
        self.alpha_label.pack()
        self.alpha_slider = ctk.CTkSlider(self.sidebar, from_=0.0, to=1.0, command=self.update_alpha)
        self.alpha_slider.set(0.6)
        self.alpha_slider.pack(pady=5, padx=20, fill="x")

        # Beta Slider (ค่าน้ำหนักความเสี่ยงน้ำท่วมหน้างาน)
        self.beta_label = ctk.CTkLabel(self.sidebar, text="Beta (เน้นหลบน้ำท่วม): 0.40")
        self.beta_label.pack()
        self.beta_slider = ctk.CTkSlider(self.sidebar, from_=0.0, to=1.0, command=self.update_beta)
        self.beta_slider.set(0.4)
        self.beta_slider.pack(pady=5, padx=20, fill="x")

        # ---- ส่วนตั้งค่าสถานการณ์ภัยพิบัติ ----
        self.disaster_label = ctk.CTkLabel(self.sidebar, text="⚠️ จำลองสภาวะน้ำท่วมขัง", font=ctk.CTkFont(weight="bold"))
        self.disaster_label.pack(pady=15)

        self.radius_label = ctk.CTkLabel(self.sidebar, text="รัศมีน้ำท่วมขัง: 2.5 กม.")
        self.radius_label.pack()
        self.radius_slider = ctk.CTkSlider(self.sidebar, from_=1.0, to=10.0, command=self.update_radius)
        self.radius_slider.set(2.5)
        self.radius_slider.pack(pady=5, padx=20, fill="x")

        # ปุ่มกดส่งพารามิเตอร์และเริ่มคำนวณผ่าน Java Engine
        self.btn_run = ctk.CTkButton(self.sidebar, text="⚡ เริ่มประมวลผลระบบกู้ภัย", fg_color="green", hover_color="darkgreen", command=self.run_simulation)
        self.btn_run.pack(pady=25, padx=20, fill="x")

        # หน้าจอ Console Log สรุปผลลัพธ์ย่อยหลังการประมวลผล
        self.log_label = ctk.CTkLabel(self.sidebar, text="📊 สรุปผลลัพธ์จาก Java Engine:", font=ctk.CTkFont(size=12, weight="bold"))
        self.log_label.pack(pady=5)
        self.txt_log = ctk.CTkTextbox(self.sidebar, height=140, width=260)
        self.txt_log.pack(pady=5, padx=10)
        self.txt_log.insert("0.0", "ระบบสแตนบาย... พร้อมรับอินพุตคำนวณเส้นทาง")

        # ================= หน้าจอแผนที่ (Interactive Map Canvas) =================
        self.map_frame = ctk.CTkFrame(self)
        self.map_frame.grid(row=0, column=1, sticky="nsew", padx=10, pady=10)

        # เรียกใช้แผนที่ผ่านไลบรารี ซูมเข้า-ออก และเลื่อนดูผังเมืองได้อิสระ
        self.map_view = TkinterMapView(self.map_frame, corner_radius=10)
        self.map_view.pack(fill="both", expand=True)
        self.map_view.set_position(7.01, 100.47) # ปรับพิกัดศูนย์กลางไปที่หาดใหญ่
        self.map_view.set_zoom(13)

        # ปักหมุดโซนอันตรายน้ำท่วมขังสีแดง (แก้ไขปัญหาไม่มีแอตทริบิวต์ set_circle)
        self.flood_marker = self.map_view.set_marker(7.01, 100.47, text="⚠️ โซนน้ำท่วมขัง (รัศมี 2.5 กม.)")
        self.map_view.set_marker(7.018, 100.465, text="🏢 คลังเสบียงหลัก (Node 4970)")
        self.map_view.set_marker(7.005, 100.475, text="⛺ ค่ายประสบภัย (Node 4957)")

    # --- ฟังก์ชันกลุ่มอัปเดตข้อมูลบนหน้าจอเมื่อผู้ใช้เลื่อนสไลเดอร์ ---
    def update_alpha(self, value):
        self.alpha_label.configure(text=f"Alpha (เน้นความเร็วเส้นทาง): {value:.2f}")

    def update_beta(self, value):
        self.beta_label.configure(text=f"Beta (เน้นหลบน้ำท่วม): {value:.2f}")

    def update_radius(self, value):
        self.radius_label.configure(text=f"รัศมีน้ำท่วมขัง: {value:.1f} กม.")
        # อัปเดตข้อความแจ้งเตือนขนาดรัศมีบนหมุดสีแดงแบบเรียลไทม์ตามนิ้วมือผู้ใช้
        self.flood_marker.set_text(f"⚠️ โซนน้ำท่วมขัง (รัศมี {value:.1f} กม.)")

    def upload_map(self):
        # เปิดหน้าต่างให้ผู้ใช้เบราว์เลือกไฟล์แผนที่โครงข่ายในคอมพิวเตอร์
        file_path = tk.filedialog.askopenfilename(filetypes=[("GraphML files", "*.graphml")])
        if file_path:
            filename = os.path.basename(file_path)
            self.map_status.configure(text=f"แผนที่ปัจจุบัน: {filename}", text_color="blue")

    # ================= ฟังก์ชันสั่งรันและข้ามระบบไปเรียกใช้ Java Engine หลังบ้าน =================
    def run_simulation(self):
        self.txt_log.delete("0.0", "end")
        self.txt_log.insert("0.0", "[🔄 Processing] ส่งข้อมูลให้ Java Engine คำนวณ...")
        self.update()

        # 1. บันทึกค่าพารามิเตอร์ล่าสุดจาก UI ลงไฟล์ JSON เพื่อส่งเป็นอินพุตให้ Java
        config_data = {
            "alpha": self.alpha_slider.get(),
            "beta": self.beta_slider.get(),
            "flood_radius": self.radius_slider.get()
        }
        with open("ui_config.json", "w") as f:
            json.dump(config_data, f)

        try:
            # ล็อกพาธ Java 17 ตัวในเครื่องของนายที่ระบบหาเจอแน่นอน
            java_compiler = "C:\\Program Files\\Java\\jdk-17\\bin\\javac.exe"
            java_runner = "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe"
            
            # ตรวจสอบความปลอดภัยว่าไฟล์ตัวรันมีอยู่จริงไหม
            if not os.path.exists(java_compiler):
                java_compiler = "javac"
                java_runner = "java"
            # -----------------------------------------------------------------
            # 🔄 สเต็ปที่ 1: ล้างไฟล์เก่า และคอมไพล์โค้ด Java ทุกตัวใหม่พร้อมกันทั้งหมด
            # -----------------------------------------------------------------
            self.txt_log.delete("0.0", "end")
            self.txt_log.insert("0.0", "[🛠️ Clean & Compile] กำลังกวาดล้างและคอมไพล์โค้ดระบบด้วยคลังไลบรารี JGraphT...")
            self.update()
            
           # 1. ลบไฟล์ .class เก่า ๆ ออกให้หมดเพื่อป้องกันเรื่องเวอร์ชันตีกัน
            out_dir = "out/production/loegis"
            if os.path.exists(out_dir):
                for root, dirs, files in os.walk(out_dir):
                    for file in files:
                        if file.endswith(".class"):
                            try:
                                os.remove(os.path.join(root, file))
                            except Exception:
                                pass

            # -----------------------------------------------------------------
            # 📦 2. แก้บั๊กจุดตาย: สแกนหาไฟล์ .jar ทุกตัวในโฟลเดอร์ lib แบบเจาะจงรายชื่อ
            # -----------------------------------------------------------------
            lib_dir = "lib"
            jar_files = []
            
            # ตรวจสอบว่ามีโฟลเดอร์ lib อยู่จริงไหม ถ้าไม่มีสร้างขึ้นมาแจ้งเตือนก่อน
            if os.path.exists(lib_dir):
                for root, dirs, files in os.walk(lib_dir):
                    for file in files:
                        if file.endswith(".jar"):
                            # เก็บพาธไฟล์ .jar ทั้งหมดแปลงเป็นรูปแบบที่ Java ชอบ
                            jar_path = os.path.join(root, file).replace("\\", "/")
                            jar_files.append(jar_path)
            
            # ถ้านายวางไฟล์ .jar ไว้กระจัดกระจาย หรือต้องการดึงพาธตรง ๆ มาต่อกัน
            # นำ "src", "out/production/loegis" และรายชื่อไฟล์ .jar ทั้งหมดมาเชื่อมด้วยเครื่องหมายเซมิโคลอน (;)
            if jar_files:
                classpath_compile = "src;" + ";".join(jar_files)
            else:
                # ป้องกันกรณีหาโฟลเดอร์ lib หรือ .jar ไม่เจอเลย
                classpath_compile = "src;lib/*"
                print("⚠️ Warning: ไม่พบไฟล์ .jar ในโฟลเดอร์ lib กรุณาตรวจสอบตำแหน่งไฟล์")

            # 3. เตรียมกวาดไฟล์ซอร์สโค้ด .java ทั้งหมดมาคอมไพล์
            java_files = []
            for root, dirs, files in os.walk("src"):
                for file in files:
                    if file.endswith(".java"):
                        java_files.append(os.path.join(root, file).replace("\\", "/"))
            
            with open("sources.txt", "w", encoding="utf-8") as f:
                f.write("\n".join(java_files))

            # 4. ยิงคำสั่งคอมไพล์รอบชี้ชะตา
            compile_result = subprocess.run(
                [
                    java_compiler, 
                    "-encoding", "UTF-8", 
                    "-cp", classpath_compile, 
                    "-d", out_dir, 
                    "@sources.txt"
                ],
                capture_output=True, text=True
            )
            
            if os.path.exists("sources.txt"):
                os.remove("sources.txt")
            
            if compile_result.returncode != 0:
                raise Exception(f"คอมไพล์โค้ดไม่ผ่าน:\n{compile_result.stderr}")
            # -----------------------------------------------------------------
            # 🏃‍♂️ สเต็ปที่ 3: สั่งรัน Java Engine ด้วย Classpath ที่ผูกไลบรารีครบถ้วน
            # -----------------------------------------------------------------
            self.txt_log.delete("0.0", "end")
            self.txt_log.insert("0.0", "[🔄 Processing] มด ACO กำลังวิ่งหาเส้นทางเสบียง...")
            self.update()

            # ตอนรันจริงก็ต้องพ่วงไฟล์ .jar เหล่านั้นเข้าไปด้วยเช่นกันเพื่อให้ระบบรู้จัก
            classpath_run = "out/production/loegis;lib/*"

            result = subprocess.run(
                [java_runner, "-cp", classpath_run, "src.Main"], 
                capture_output=True, text=True, check=True
            )

        except Exception as e:
            self.txt_log.delete("0.0", "end")
            error_msg = str(e)
            if hasattr(e, 'stderr') and e.stderr:
                error_msg += f"\n\n[Java Details]:\n{e.stderr}"
            self.txt_log.insert("0.0", f"❌ ระบบเกิดข้อผิดพลาด\nDetail: {error_msg}")
if __name__ == "__main__":
    app = LoegisUI()
    app.mainloop()