// ============================================================================
// Crisis Logistics Console — frontend app logic
// รองรับระบบน้ำท่วมหลายจุด และเลือกความลึกได้: ตื้น / กลาง / ลึก
// ============================================================================

const state = {
  map: null,
  nodeLayer: null,
  edgeLayer: null,
  routeLayer: null,
  markerLayer: null,
  floodLayer: null,     // ใช้วาดวงน้ำท่วมหลายจุด
  nodesById: new Map(),
  clickMode: 'none',
  lastLogCount: 0,
};

const els = {};

let selectedFloodLevel = 'MEDIUM';

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
    'flood-lat', 'flood-lon', 'flood-radius', 'flood-level',
    'btn-simulate-flood', 'btn-clear-flood',
    'demand-water', 'demand-medical', 'time-start', 'time-end',
    'fleet-size', 'vehicle-capacity', 'btn-setup-fleet',
    'aco-alpha', 'aco-beta', 'aco-iterations', 'btn-run-aco',
    'btn-reset', 'btn-clear-log','btn-run-route',
    'connection-status', 'log-lines',
    'stat-nodes', 'stat-edges', 'stat-risk', 'stat-blocked', 'stat-cost',
    'mode-none', 'mode-depot', 'mode-camp', 'mode-flood','traffic-mode','mode-rescue',
  ];

  ids.forEach(id => {
    els[id] = document.getElementById(id);
  });
}

// ============================================================================
// MAP SETUP
// ============================================================================

function initMap() {
  state.map = L.map('map', {
    zoomControl: true,
    minZoom: 3,
    maxZoom: 19,
    worldCopyJump: false,
  }).setView([7.0061, 100.4681], 14);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19,
  }).addTo(state.map);

  state.edgeLayer = L.layerGroup().addTo(state.map);
  state.nodeLayer = L.layerGroup().addTo(state.map);
  state.floodLayer = L.layerGroup().addTo(state.map);
  state.routeLayer = L.layerGroup().addTo(state.map);
  state.markerLayer = L.layerGroup().addTo(state.map);

  state.map.on('click', onMapClick);
}

function onMapClick(e) {
  if (state.clickMode === 'flood') {
    els['flood-lat'].value = e.latlng.lat.toFixed(6);
    els['flood-lon'].value = e.latlng.lng.toFixed(6);

    apiPost('/api/addFloodZone', {
      lat: e.latlng.lat,
      lon: e.latlng.lng,
      radiusKm: numVal('flood-radius', 1.0),
      level: selectedFloodLevel,
    }).then(renderFromState);

    log(`[🌊] เพิ่มจุดน้ำท่วมระดับ ${floodLevelThai(selectedFloodLevel)} ที่ (${e.latlng.lat.toFixed(4)}, ${e.latlng.lng.toFixed(4)})`);

    return;
  }

  if (
      state.clickMode !== 'depot' &&
      state.clickMode !== 'camp' &&
      state.clickMode !== 'rescue'
  ) return;

  const nearest = findNearestNode(e.latlng.lat, e.latlng.lng);

  if (!nearest) {
    log('[⚠️] ยังไม่มีโหนดบนแผนที่ — กรุณาโหลดแผนที่ก่อน');
    return;
  }

  if (state.clickMode === 'depot') {
    apiPost('/api/setDepot', {
      nodeId: nearest.id,
    }).then(renderFromState);
  }

  if (state.clickMode === 'camp') {
    apiPost('/api/setCamp', {
      nodeId: nearest.id,
      water: numVal('demand-water', 500),
      medical: numVal('demand-medical', 50),
      startMin: numVal('time-start', 60),
      endMin: numVal('time-end', 180),
    }).then(renderFromState);
  }

  if (state.clickMode === 'rescue') {
    apiPost('/api/addRescuePoint', {
      nodeId: nearest.id,
    }).then(renderFromState);
  }

  setClickMode('none');
}

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
// CLICK MODE
// ============================================================================

function setClickMode(mode) {
  state.clickMode = mode;

  ['none', 'depot', 'camp', 'rescue'].forEach(m => {
    const btn = els['mode-' + m];
    if (btn) btn.classList.toggle('active', m === mode);
  });

  if (els['mode-flood']) {
    els['mode-flood'].classList.toggle('active', mode === 'flood');
  }

  const mapDiv = document.getElementById('map-container');
  mapDiv.classList.toggle('map-cursor-crosshair', mode !== 'none');
}

// ============================================================================
// UI EVENTS
// ============================================================================

function bindUiEvents() {
  els['mode-none'].addEventListener('click', () => setClickMode('none'));
  els['mode-depot'].addEventListener('click', () => setClickMode('depot'));
  els['mode-camp'].addEventListener('click', () => setClickMode('camp'));
  els['mode-flood'].addEventListener('click', () => setClickMode('flood'));
  els['mode-rescue'].addEventListener('click', () => setClickMode('rescue'));

  els['flood-level'].addEventListener('change', () => {
    selectedFloodLevel = els['flood-level'].value;
    log(`[🌊] เลือกระดับน้ำท่วม: ${floodLevelThai(selectedFloodLevel)}`);
  });

  // =========================================================
  // Traffic Mode Change
  // เมื่อเปลี่ยนโหมดจราจร ให้โหลดแผนที่ใหม่อัตโนมัติ
  //
  // true  = เคารพ One-way
  // false = Emergency Mode / รถกู้ภัยย้อนศรได้
  // =========================================================
  els['traffic-mode'].addEventListener('change', () => {
    const respectOneWay = els['traffic-mode'].value === 'true';

    log(
        respectOneWay
            ? '[🚦] เปลี่ยนเป็นโหมดจราจรจริง — ไม่ต้องโหลดแผนที่ใหม่'
            : '[🚨] เปลี่ยนเป็น Emergency Mode — เปิดทางย้อนศรทันที'
    );

    apiPost('/api/setTrafficMode', {
      respectOneWay: respectOneWay,
    }).then(renderFromState);
  });

  els['btn-load-map'].addEventListener('click', () => {
    apiPost('/api/load', {
      mapFilePath: els['map-file-path'].value.trim(),
      respectOneWay: els['traffic-mode'].value === 'true',
    }).then(data => {
      renderFromState(data);
      if (data.loaded) fitMapToNodes();
    });
  });

  els['btn-simulate-flood'].addEventListener('click', () => {
    apiPost('/api/addFloodZone', {
      lat: numVal('flood-lat', 7.01),
      lon: numVal('flood-lon', 100.47),
      radiusKm: numVal('flood-radius', 2.5),
      level: selectedFloodLevel,
    }).then(renderFromState);
  });

  els['btn-clear-flood'].addEventListener('click', () => {
    apiPost('/api/clearFloodZones', {}).then(renderFromState);
  });

  els['btn-setup-fleet'].addEventListener('click', () => {
    apiPost('/api/setupFleet', {
      fleetSize: numVal('fleet-size', 2),
      capacity: numVal('vehicle-capacity', 1000),
    }).then(renderFromState);
  });

  els['btn-run-route'].addEventListener('click', () => {

    els['btn-run-route'].disabled = true;
    els['btn-run-route'].textContent = '⏳ กำลังคำนวณ...';

    apiPost('/api/runRoute', {})
        .then(renderFromState)
        .finally(() => {

          els['btn-run-route'].disabled = false;
          els['btn-run-route'].textContent =
              '🚀 หาเส้นทางเลี่ยงที่ดีที่สุด';

        });

  });

  els['btn-run-aco'].addEventListener('click', () => {
    els['btn-run-aco'].disabled = true;
    els['btn-run-aco'].textContent = '⏳ กำลังวางแผนกู้ภัย...';

    apiPost('/api/runRescueMission', {})
        .then(renderFromState)
        .finally(() => {
          els['btn-run-aco'].disabled = false;
          els['btn-run-aco'].textContent = '🚨 วางแผนช่วยเหลือผู้ประสบภัย';
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
// API
// ============================================================================

function apiPost(path, body) {
  return fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
    body: JSON.stringify(body),
  })
      .then(res => res.json())
      .then(data => {
        setConnectionStatus(true);
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
      .then(data => {
        setConnectionStatus(true);
        return data;
      })
      .catch(err => {
        setConnectionStatus(false);
        throw err;
      });
}

function refreshState() {
  apiGet('/api/state')
      .then(renderFromState)
      .catch(() => {});
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
// RENDER
// ============================================================================

function renderFromState(data) {
  if (!data) return;

  if (!data.loaded) {
    els['stat-nodes'].textContent = '—';
    els['stat-edges'].textContent = '—';
    els['stat-risk'].textContent = '—';
    els['stat-blocked'].textContent = '—';
    els['stat-cost'].textContent = '—';

    state.edgeLayer?.clearLayers();
    state.nodeLayer?.clearLayers();
    state.routeLayer?.clearLayers();
    state.markerLayer?.clearLayers();
    state.floodLayer?.clearLayers();

    return;
  }

  state.nodesById = new Map(data.nodes.map(n => [n.id, n]));

  renderEdges(data.edges, data.routeEdgeIds || []);
  renderNodes(data.nodes, data.depotId, data.campId, data.rescuePointIds || []);
  renderFloodZones(data.floodZones || []);
  renderRoute(data.edges, data.routeEdgeIds || [], data.nodes);

  els['stat-nodes'].textContent = data.nodes.length;
  els['stat-edges'].textContent = data.edges.length;

  const riskyCount = data.edges.filter(e => e.riskLevel > 0).length;
  els['stat-risk'].textContent = riskyCount;

  els['stat-blocked'].textContent = data.blockedEdges ?? 0;
  els['stat-cost'].textContent = data.mockRouteCost ? data.mockRouteCost.toFixed(1) : '—';
}

function renderEdges(edges, routeEdgeIds) {
  state.edgeLayer.clearLayers();

  const routeSet = new Set(routeEdgeIds);
  const nodeMap = state.nodesById;

  for (const edge of edges) {
    if (routeSet.has(edge.id)) continue;
    if (edge.trafficAllowed === false) continue;

    const source = nodeMap.get(edge.sourceId);
    const target = nodeMap.get(edge.targetId);

    if (!source || !target) continue;

    let color = '#3a4255';
    let weight = 1.6;
    let opacity = 0.55;
    let dashArray = null;

    if (edge.passable === false) {
      color = '#111111';
      weight = 4;
      opacity = 0.95;
      dashArray = '8, 6';
    } else if (edge.riskLevel > 0) {
      color = riskColor(edge.riskLevel);
      weight = 3;
      opacity = 0.85;
    }

    L.polyline([[source.lat, source.lon], [target.lat, target.lon]], {
      color,
      weight,
      opacity,
      dashArray,
    }).addTo(state.edgeLayer);
  }
}

function renderNodes(nodes, depotId, campId, dataRescueIds) {
  state.nodeLayer.clearLayers();
  state.markerLayer.clearLayers();

  for (const node of nodes) {
    if (node.id === depotId || node.id === campId) continue;

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

    L.marker([d.lat, d.lon], {
      icon: makeDivIcon('🏢', '#4fd17f'),
    })
        .bindTooltip('คลังเสบียง (Depot)')
        .addTo(state.markerLayer);
  }

  if (campId && state.nodesById.has(campId)) {
    const c = state.nodesById.get(campId);

    L.marker([c.lat, c.lon], {
      icon: makeDivIcon('⛺', '#f0524a'),
    })
        .bindTooltip('ค่ายผู้ประสบภัย (Demand)')
        .addTo(state.markerLayer);
  }

  if (dataRescueIds && Array.isArray(dataRescueIds)) {
    for (const rescueId of dataRescueIds) {
      if (!state.nodesById.has(rescueId)) continue;

      const r = state.nodesById.get(rescueId);

      L.marker([r.lat, r.lon], {
        icon: makeDivIcon('🆘', '#f97316'),
      })
          .bindTooltip('ผู้ประสบภัย (Rescue Point)')
          .addTo(state.markerLayer);
    }
  }

}

function renderFloodZones(floodZones) {
  state.floodLayer.clearLayers();

  for (const zone of floodZones) {
    const color = floodLevelColor(zone.level);

    L.circle([zone.lat, zone.lon], {
      radius: zone.radiusKm * 1000,
      color,
      fillColor: color,
      fillOpacity: 0.18,
      weight: 2,
      opacity: 0.9,
    })
        .bindTooltip(`น้ำท่วมระดับ ${floodLevelThai(zone.level)} | รัศมี ${zone.radiusKm} กม.`)
        .addTo(state.floodLayer);

    L.marker([zone.lat, zone.lon], {
      icon: L.divIcon({
        html: `<div style="
          width: 28px;
          height: 28px;
          border-radius: 50%;
          background: ${color};
          color: white;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 15px;
          border: 2px solid white;
          box-shadow: 0 2px 8px rgba(0,0,0,0.35);
        ">🌊</div>`,
        className: '',
        iconSize: [28, 28],
        iconAnchor: [14, 14],
      }),
      interactive: false,
    }).addTo(state.floodLayer);
  }
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

    if (latlngs.length === 0) {
      latlngs.push([source.lat, source.lon]);
    }

    latlngs.push([target.lat, target.lon]);
  }

  if (latlngs.length < 2) return;

  L.polyline(latlngs, {
    color: '#06141a',
    weight: 9,
    opacity: 0.45,
  }).addTo(state.routeLayer);

  const mainLine = L.polyline(latlngs, {
    color: '#3fd3e0',
    weight: 5,
    opacity: 0.95,
    lineCap: 'round',
    lineJoin: 'round',
  }).addTo(state.routeLayer);

  addDirectionArrows(latlngs);

  state.map.fitBounds(mainLine.getBounds(), {
    padding: [60, 60],
    maxZoom: 17,
  });
}

// ============================================================================
// STYLE HELPERS
// ============================================================================

function riskColor(risk) {

  // น้ำตื้น
  if (risk < 0.45) {
    return "#f4c542";      // เหลือง
  }

  // น้ำกลาง
  if (risk < 0.85) {
    return "#e53935";      // แดง
  }

  // น้ำลึก
  return "#111111";          // ดำ
}
function floodLevelColor(level) {
  if (level === 'SHALLOW') return '#4fd17f';
  if (level === 'MEDIUM') return '#f3a23d';
  if (level === 'DEEP') return '#f0524a';
  return '#f3a23d';
}

function floodLevelThai(level) {
  if (level === 'SHALLOW') return 'ตื้น';
  if (level === 'MEDIUM') return 'กลาง';
  if (level === 'DEEP') return 'ลึก';
  return 'กลาง';
}

function makeDivIcon(emoji, color) {
  return L.divIcon({
    html: `<div style="
      width: 30px;
      height: 30px;
      border-radius: 8px;
      background: ${color};
      display:flex;
      align-items:center;
      justify-content:center;
      font-size: 16px;
      border: 2px solid rgba(255,255,255,0.85);
      box-shadow: 0 2px 8px rgba(0,0,0,0.4);
    ">${emoji}</div>`,
    className: '',
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  });
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
        width: 0;
        height: 0;
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

    L.marker([midLat, midLon], {
      icon,
      interactive: false,
    }).addTo(state.routeLayer);
  }
}

function fitMapToNodes() {
  if (state.nodesById.size === 0) return;

  const bounds = [];

  for (const node of state.nodesById.values()) {
    bounds.push([node.lat, node.lon]);
  }

  state.map.fitBounds(bounds, {
    padding: [40, 40],
  });
}

// ============================================================================
// LOG
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
  apiGet('/api/logs')
      .then(data => {
        const lines = data.lines || [];

        if (lines.length > state.lastLogCount) {
          const newLines = lines.slice(state.lastLogCount);
          newLines.forEach(l => log(l, classifyLogLevel(l)));
          state.lastLogCount = lines.length;
        } else if (lines.length < state.lastLogCount) {
          els['log-lines'].innerHTML = '';
          lines.forEach(l => log(l, classifyLogLevel(l)));
          state.lastLogCount = lines.length;
        }
      })
      .catch(() => {})
      .finally(() => {
        setTimeout(pollLogs, 1200);
      });
}