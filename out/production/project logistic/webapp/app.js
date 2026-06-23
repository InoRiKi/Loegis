// ============================================================================
// Crisis Logistics Console — frontend app logic
//
// ใช้ Leaflet.js เป็นแผนที่จริง (รองรับ zoom/pan ที่ถูกต้องตามมาตรฐานแผนที่เว็บ
// แก้ปัญหาเดิมที่ custom Swing renderer ซูมแล้วพัง เพราะ Leaflet จัดการ projection
// และ zoom level ให้เราเองทั้งหมด ไม่ต้องคำนวณ pixel-per-degree เองแบบเดิม)
// ============================================================================

const state = {
  map: null,
  nodeLayer: null,      // L.LayerGroup ของจุดโหนดทั้งหมด (วงกลมเล็กๆ)
  edgeLayer: null,       // L.LayerGroup ของเส้นถนนทั้งหมด
  routeLayer: null,      // L.LayerGroup ของเส้นทางที่ ACO เลือก (วาดทับบนสุด)
  markerLayer: null,     // L.LayerGroup ของ marker depot/camp ขนาดใหญ่
  nodesById: new Map(),  // nodeId -> {lat, lon, ...} เก็บไว้ใช้ตอนคลิกเลือกจุดที่ใกล้ที่สุด
  clickMode: 'none',     // 'none' | 'depot' | 'camp' | 'flood'
  lastLogCount: 0,
};

const els = {}; // จะ populate ด้วย id ทั้งหมดที่ใช้บ่อย ผ่าน initElementRefs()

// ============================================================================
// INIT
// ============================================================================

document.addEventListener('DOMContentLoaded', () => {
  initElementRefs();
  initMap();
  bindUiEvents();
  refreshState();
  pollLogs();
});

function initElementRefs() {
  const ids = [
    'map-file-path', 'btn-load-map',
    'flood-lat', 'flood-lon', 'flood-radius', 'btn-simulate-flood',
    'demand-water', 'demand-medical', 'time-start', 'time-end',
    'fleet-size', 'vehicle-capacity', 'btn-setup-fleet',
    'aco-alpha', 'aco-beta', 'aco-iterations', 'btn-run-aco',
    'btn-reset', 'btn-clear-log',
    'connection-status', 'log-lines',
    'stat-nodes', 'stat-edges', 'stat-risk', 'stat-cost',
    'mode-none', 'mode-depot', 'mode-camp', 'mode-flood',
  ];
  ids.forEach(id => { els[id] = document.getElementById(id); });
}

// ============================================================================
// MAP SETUP (Leaflet)
// ============================================================================

function initMap() {
  // ค่าเริ่มต้นชี้ไปที่หาดใหญ่ (ตรงกับพิกัดเริ่มต้นของระบบเดิมใน Main.java)
  state.map = L.map('map', {
    zoomControl: true,
    minZoom: 3,
    maxZoom: 19,        // Leaflet จัดการ zoom level มาตรฐานเว็บแมพให้ ไม่มีปัญหาบัคซูมแบบ custom renderer เดิม
    worldCopyJump: false,
  }).setView([7.0061, 100.4681], 14);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19,
  }).addTo(state.map);

  state.edgeLayer = L.layerGroup().addTo(state.map);
  state.nodeLayer = L.layerGroup().addTo(state.map);
  state.routeLayer = L.layerGroup().addTo(state.map);
  state.markerLayer = L.layerGroup().addTo(state.map);

  // คลิกบนแผนที่ -> ถ้าอยู่ในโหมดเลือกจุด ให้หาโหนดที่ใกล้ที่สุดแล้วสั่ง API ตั้งค่า
  state.map.on('click', onMapClick);
}

function onMapClick(e) {
  if (state.clickMode === 'flood') {
    // โหมดตั้งจุดน้ำท่วม: ใช้พิกัดที่คลิกตรงๆ ไม่ต้อง snap เข้าโหนด เพราะน้ำท่วมเป็นพิกัดอิสระ
    els['flood-lat'].value = e.latlng.lat.toFixed(6);
    els['flood-lon'].value = e.latlng.lng.toFixed(6);
    log(`[📍] ตั้งจุดน้ำท่วมที่ (${e.latlng.lat.toFixed(4)}, ${e.latlng.lng.toFixed(4)}) — กดปุ่ม "จำลองน้ำท่วม" เพื่อยืนยัน`);
    setClickMode('none');
    return;
  }

  if (state.clickMode !== 'depot' && state.clickMode !== 'camp') return;

  const nearest = findNearestNode(e.latlng.lat, e.latlng.lng);
  if (!nearest) {
    log('[⚠️] ยังไม่มีโหนดบนแผนที่ — กรุณาโหลดแผนที่ก่อน');
    return;
    }

  if (state.clickMode === 'depot') {
    apiPost('/api/setDepot', { nodeId: nearest.id }).then(renderFromState);
  } else if (state.clickMode === 'camp') {
    apiPost('/api/setCamp', {
      nodeId: nearest.id,
      water: numVal('demand-water', 500),
      medical: numVal('demand-medical', 50),
      startMin: numVal('time-start', 60),
      endMin: numVal('time-end', 180),
    }).then(renderFromState);
  }

  setClickMode('none'); // เลือกครั้งเดียวแล้วกลับโหมดเลื่อนแผนที่ปกติ กันคลิกเผลอตั้งซ้ำ
}

/** หาโหนดที่ใกล้พิกัดที่คลิกที่สุด (ระยะทางตรง ไม่ใช่ Haversine เพราะพื้นที่เล็กระดับเมือง ความแม่นยำพอกัน) */
function findNearestNode(lat, lon) {
  let best = null;
  let bestDist = Infinity;
  for (const node of state.nodesById.values()) {
    const dLat = node.lat - lat;
    const dLon = node.lon - lon;
    const dist = dLat * dLat + dLon * dLon;
    if (dist < bestDist) {
      bestDist = dist;
      best = node;
    }
  }
  return best;
}

// ============================================================================
// CLICK MODE (depot / camp / flood selection)
// ============================================================================

function setClickMode(mode) {
  state.clickMode = mode;
  ['none', 'depot', 'camp'].forEach(m => {
    const btn = els['mode-' + m];
    if (btn) btn.classList.toggle('active', m === mode);
  });
  // ปุ่มตั้งจุดน้ำท่วมอยู่ในพาแนลแยก ไม่ใช่กลุ่ม mode-buttons หลัก จัดการ active state เองตรงนี้
  els['mode-flood'].classList.toggle('active', mode === 'flood');

  const mapDiv = document.getElementById('map-container');
  mapDiv.classList.toggle('map-cursor-crosshair', mode !== 'none');
}

function bindUiEvents() {
  els['mode-none'].addEventListener('click', () => setClickMode('none'));
  els['mode-depot'].addEventListener('click', () => setClickMode('depot'));
  els['mode-camp'].addEventListener('click', () => setClickMode('camp'));
  els['mode-flood'].addEventListener('click', () => setClickMode('flood'));

  els['btn-load-map'].addEventListener('click', () => {
    apiPost('/api/load', { mapFilePath: els['map-file-path'].value.trim() })
      .then(data => {
        renderFromState(data);
        if (data.loaded) fitMapToNodes();
      });
  });

  els['btn-simulate-flood'].addEventListener('click', () => {
    apiPost('/api/simulateFlood', {
      lat: numVal('flood-lat', 7.01),
      lon: numVal('flood-lon', 100.47),
      radiusKm: numVal('flood-radius', 2.5),
    }).then(renderFromState);
  });

  els['btn-setup-fleet'].addEventListener('click', () => {
    apiPost('/api/setupFleet', {
      fleetSize: numVal('fleet-size', 2),
      capacity: numVal('vehicle-capacity', 1000),
    }).then(renderFromState);
  });

  els['btn-run-aco'].addEventListener('click', () => {
    els['btn-run-aco'].disabled = true;
    els['btn-run-aco'].textContent = '⏳ กำลังคำนวณ...';
    apiPost('/api/runAco', {
      alpha: numVal('aco-alpha', 0.6),
      beta: numVal('aco-beta', 0.4),
      iterations: numVal('aco-iterations', 100),
    }).then(renderFromState).finally(() => {
      els['btn-run-aco'].disabled = false;
      els['btn-run-aco'].textContent = '🚀 หาเส้นทางที่ดีที่สุด';
    });
  });

  els['btn-reset'].addEventListener('click', () => {
    apiPost('/api/reset', {}).then(data => {
      renderFromState(data);
      setClickMode('none');
    });
  });

  els['btn-clear-log'].addEventListener('click', () => {
    els['log-lines'].innerHTML = '';
  });
}

// ============================================================================
// API HELPERS
// ============================================================================

function apiPost(path, body) {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify(body),
  })
    .then(res => res.json())
    .then(data => {
      setConnectionStatus(true);
      if (data.ok === false) {
        // ok:false ไม่ใช่ network error — แค่ step นั้นทำไม่สำเร็จ (เช่นยังไม่ตั้ง depot) ไม่ต้อง alert รก
      }
      return data;
    })
    .catch(err => {
      setConnectionStatus(false);
      log('[❌ Network Error] ติดต่อเซิร์ฟเวอร์ไม่ได้: ' + err.message);
      throw err;
    });
}

function apiGet(path) {
  return fetch(path)
    .then(res => res.json())
    .then(data => { setConnectionStatus(true); return data; })
    .catch(err => { setConnectionStatus(false); throw err; });
}

function refreshState() {
  apiGet('/api/state').then(renderFromState).catch(() => {});
}

function setConnectionStatus(ok) {
  const el = els['connection-status'];
  el.classList.remove('status-unknown', 'status-ok', 'status-error');
  if (ok) {
    el.classList.add('status-ok');
    el.innerHTML = '<span class="dot"></span> เชื่อมต่อสำเร็จ';
  } else {
    el.classList.add('status-error');
    el.innerHTML = '<span class="dot"></span> ขาดการเชื่อมต่อ';
  }
}

function numVal(id, fallback) {
  const v = parseFloat(els[id].value);
  return Number.isFinite(v) ? v : fallback;
}

// ============================================================================
// RENDERING — วาดข้อมูลกราฟ/เส้นทางจาก state JSON ลงบน Leaflet map
// ============================================================================

function renderFromState(data) {
  if (!data) return;

  if (!data.loaded) {
    els['stat-nodes'].textContent = '—';
    els['stat-edges'].textContent = '—';
    els['stat-risk'].textContent = '—';
    els['stat-cost'].textContent = '—';
    return;
  }

  state.nodesById = new Map(data.nodes.map(n => [n.id, n]));

  renderEdges(data.edges, data.routeEdgeIds || []);
  renderNodes(data.nodes, data.depotId, data.campId);
  renderRoute(data.edges, data.routeEdgeIds || [], data.nodes);

  els['stat-nodes'].textContent = data.nodes.length;
  els['stat-edges'].textContent = data.edges.length;
  const riskyCount = data.edges.filter(e => e.riskLevel > 0).length;
  els['stat-risk'].textContent = riskyCount;
  els['stat-cost'].textContent = data.mockRouteCost ? data.mockRouteCost.toFixed(1) : '—';
}

function renderEdges(edges, routeEdgeIds) {
  state.edgeLayer.clearLayers();
  const routeSet = new Set(routeEdgeIds);
  const nodeMap = state.nodesById;

  for (const edge of edges) {
    if (routeSet.has(edge.id)) continue; // เส้นทาง ACO วาดแยกทับด้านบนใน renderRoute() ไม่วาดซ้ำตรงนี้

    const source = nodeMap.get(edge.sourceId);
    const target = nodeMap.get(edge.targetId);
    if (!source || !target) continue;

    const isRisky = edge.riskLevel > 0;
    const color = isRisky ? riskColor(edge.riskLevel) : '#3a4255';
    const weight = isRisky ? 3 : 1.6;
    const opacity = isRisky ? 0.85 : 0.55;

    L.polyline([[source.lat, source.lon], [target.lat, target.lon]], {
      color, weight, opacity,
    }).addTo(state.edgeLayer);
  }
}

function riskColor(risk) {
  // ไล่สีเหลือง (เสี่ยงต่ำ) -> แดง (เสี่ยงสูง) ตาม riskLevel 0.0–1.0
  const t = Math.max(0, Math.min(1, risk));
  const r1 = [243, 162, 61], r2 = [240, 82, 74]; // amber -> red
  const r = Math.round(r1[0] + t * (r2[0] - r1[0]));
  const g = Math.round(r1[1] + t * (r2[1] - r1[1]));
  const b = Math.round(r1[2] + t * (r2[2] - r1[2]));
  return `rgb(${r},${g},${b})`;
}

function renderNodes(nodes, depotId, campId) {
  state.nodeLayer.clearLayers();
  state.markerLayer.clearLayers();

  for (const node of nodes) {
    if (node.id === depotId || node.id === campId) continue; // วาด depot/camp แยกให้เด่นด้านล่าง

    L.circleMarker([node.lat, node.lon], {
      radius: 2,
      color: '#5a6376',
      fillColor: '#5a6376',
      fillOpacity: 0.6,
      weight: 0,
    }).addTo(state.nodeLayer);
  }

  if (depotId && state.nodesById.has(depotId)) {
    const d = state.nodesById.get(depotId);
    L.marker([d.lat, d.lon], { icon: makeDivIcon('🏢', '#4fd17f') })
      .bindTooltip('คลังเสบียง (Depot)', { permanent: false })
      .addTo(state.markerLayer);
  }

  if (campId && state.nodesById.has(campId)) {
    const c = state.nodesById.get(campId);
    L.marker([c.lat, c.lon], { icon: makeDivIcon('⛺', '#f0524a') })
      .bindTooltip('ค่ายผู้ประสบภัย (Demand)', { permanent: false })
      .addTo(state.markerLayer);
  }
}

function makeDivIcon(emoji, color) {
  return L.divIcon({
    html: `<div style="
      width: 30px; height: 30px; border-radius: 8px;
      background: ${color}; display:flex; align-items:center; justify-content:center;
      font-size: 16px; border: 2px solid rgba(255,255,255,0.85);
      box-shadow: 0 2px 8px rgba(0,0,0,0.4);
    ">${emoji}</div>`,
    className: '',
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  });
}

function renderRoute(edges, routeEdgeIds, nodes) {
  state.routeLayer.clearLayers();
  if (!routeEdgeIds || routeEdgeIds.length === 0) return;

  const nodeMap = state.nodesById;
  const edgeById = new Map(edges.map(e => [e.id, e]));
  const latlngs = [];

  for (const id of routeEdgeIds) {
    const edge = edgeById.get(id);
    if (!edge) continue;
    const source = nodeMap.get(edge.sourceId);
    const target = nodeMap.get(edge.targetId);
    if (!source || !target) continue;
    if (latlngs.length === 0) latlngs.push([source.lat, source.lon]);
    latlngs.push([target.lat, target.lon]);
  }

  if (latlngs.length < 2) return;

  // เส้น halo สีเข้มด้านล่างให้เส้นทางเด้งเด่นจากพื้นหลังแผนที่ ก่อนวาดเส้นสีฟ้าทับด้านบน
  L.polyline(latlngs, { color: '#06141a', weight: 9, opacity: 0.45 }).addTo(state.routeLayer);
  const mainLine = L.polyline(latlngs, {
    color: '#3fd3e0', weight: 5, opacity: 0.95, lineCap: 'round', lineJoin: 'round',
  }).addTo(state.routeLayer);

  // ลูกศรบอกทิศทางเป็นระยะ ใช้ decorator แบบง่าย ๆ ด้วย marker สามเหลี่ยม CSS ที่จุดกึ่งกลางบางช่วง
  addDirectionArrows(latlngs);

  state.map.fitBounds(mainLine.getBounds(), { padding: [60, 60], maxZoom: 17 });
}

function addDirectionArrows(latlngs) {
  const step = Math.max(1, Math.floor(latlngs.length / 6));
  for (let i = 0; i < latlngs.length - 1; i += step) {
    const [lat1, lon1] = latlngs[i];
    const [lat2, lon2] = latlngs[i + 1];
    const midLat = (lat1 + lat2) / 2;
    const midLon = (lon1 + lon2) / 2;
    const angleDeg = Math.atan2(lon2 - lon1, lat2 - lat1) * (180 / Math.PI);

    const icon = L.divIcon({
      html: `<div style="
        width: 0; height: 0;
        border-left: 6px solid transparent;
        border-right: 6px solid transparent;
        border-bottom: 10px solid #3fd3e0;
        transform: rotate(${angleDeg}deg);
        filter: drop-shadow(0 0 2px rgba(0,0,0,0.6));
      "></div>`,
      className: '',
      iconSize: [12, 10],
      iconAnchor: [6, 5],
    });
    L.marker([midLat, midLon], { icon, interactive: false }).addTo(state.routeLayer);
  }
}

function fitMapToNodes() {
  if (state.nodesById.size === 0) return;
  const bounds = [];
  for (const node of state.nodesById.values()) {
    bounds.push([node.lat, node.lon]);
  }
  state.map.fitBounds(bounds, { padding: [40, 40] });
}

// ============================================================================
// LOG CONSOLE
// ============================================================================

function log(text, level) {
  const line = document.createElement('div');
  line.className = 'log-line' + (level ? ' log-' + level : '');
  line.textContent = text;
  els['log-lines'].appendChild(line);
  els['log-lines'].scrollTop = els['log-lines'].scrollHeight;
}

function classifyLogLevel(text) {
  if (text.includes('❌') || text.includes('Error')) return 'error';
  if (text.includes('🎉') || text.includes('✓') || text.includes('Success')) return 'success';
  if (text.includes('⚠️')) return 'warn';
  return null;
}

function pollLogs() {
  apiGet('/api/logs').then(data => {
    const lines = data.lines || [];
    if (lines.length > state.lastLogCount) {
      const newLines = lines.slice(state.lastLogCount);
      newLines.forEach(l => log(l, classifyLogLevel(l)));
      state.lastLogCount = lines.length;
    } else if (lines.length < state.lastLogCount) {
      // server ถูก reset (log buffer สั้นลงกว่าที่เคยเห็น) -> sync ใหม่ทั้งหมด
      els['log-lines'].innerHTML = '';
      lines.forEach(l => log(l, classifyLogLevel(l)));
      state.lastLogCount = lines.length;
    }
  }).catch(() => {}).finally(() => {
    setTimeout(pollLogs, 1200);
  });
}
