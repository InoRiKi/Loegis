import osmnx as ox
import networkx as nx
import matplotlib.pyplot as plt

# 1. กำหนดชื่อพื้นที่ที่ต้องการ (ใช้ภาษาอังกฤษจะแม่นยำที่สุด)
# ตัวอย่าง: "Amphoe Hat Yai, Songkhla, Thailand" หรือ "Amphoe Mueang Sukhothai, Sukhothai, Thailand"
place_name = "Hat Yai District, Songkhla, Thailand"

print(f"กำลังดาวน์โหลดข้อมูลแผนที่ของ: {place_name}...")

# 2. ดึงข้อมูลโครงข่ายถนนที่รถยนต์สามารถวิ่งได้ (drive)
# OSMnx จะไปดึงข้อมูลจาก OSM และสร้างเป็น Graph Object ของ NetworkX ให้ทันที
graph = ox.graph_from_place(place_name, network_type="drive")

print(f"ดาวน์โหลดสำเร็จ! โครงข่ายกราฟมีทั้งหมด:")
print(f"- จำนวนจุดตัด (Nodes): {len(graph.nodes)}")
print(f"- จำนวนเส้นทางถนน (Edges): {len(graph.edges)}")

# 3. สั่งวาดรูปโครงข่ายกราฟเพื่อตรวจสอบความถูกต้อง
fig, ax = ox.plot_graph(graph, node_color="r", node_size=5, edge_color="#333333", edge_linewidth=0.8)
plt.show()

print("กำลังปรับฟอร์แมต ID ของถนนเพื่อป้องกันการซ้ำกันใน Java...")
# ลูปแปลงข้อมูลให้ Edge ID ในโครงสร้าง XML กลายเป็น Unique String (เช่น "u_v_key")
for u, v, k, d in graph.edges(data=True, keys=True):
    d['id'] = f"edge_{u}_{v}_{k}"

# จากนั้นค่อยสั่งเซฟไฟล์ทับตัวเดิม
ox.save_graphml(graph, filepath="hatyai_map.graphml")
print("เซฟไฟล์ใหม่เรียบร้อย! ลองนำไปรันใน Java อีกรอบครับ")