import { useEffect, useRef, useState } from "react";
import "@cesium/engine/Source/Widget/CesiumWidget.css";
import { api } from "../api/client";
import { accessToken } from "../auth/session";
import { useBuildings, useBusRoutes, useFacilities, useModels, useRegions, useRoads } from "../api/hooks";
import type { SensorReading } from "../api/types";
import { useTwin } from "../state/store";
import { FACILITY_LABELS, isochroneColor, legendFor, USE_LABELS } from "./colors";
import { TwinScene, type PickInfo } from "./TwinScene";

interface Tooltip {
  x: number;
  y: number;
  lines: string[];
}

export function TwinMap({ sensors, onReady }: { sensors: SensorReading[]; onReady?: (scene: TwinScene) => void }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const sceneRef = useRef<TwinScene | null>(null);
  const [ready, setReady] = useState(false);
  const [tooltip, setTooltip] = useState<Tooltip | null>(null);

  const layers = useTwin((s) => s.layers);
  const colorMode = useTwin((s) => s.colorMode);
  const highlight = useTwin((s) => s.highlight);
  const selectedId = useTwin((s) => s.selectedBuildingId);
  const coverage = useTwin((s) => s.coverage);
  const recommendation = useTwin((s) => s.recommendation);
  const camera = useTwin((s) => s.camera);
  const selectBuilding = useTwin((s) => s.selectBuilding);
  const isochrones = useTwin((s) => s.isochrones);
  const setIsochroneData = useTwin((s) => s.setIsochroneData);
  const toast = useTwin((s) => s.toast);

  const regions = useRegions();
  const roads = useRoads();
  const busRoutes = useBusRoutes();
  const buildings = useBuildings();
  const facilities = useFacilities();
  const models = useModels();

  useEffect(() => {
    const scene = new TwinScene(containerRef.current!);
    sceneRef.current = scene;
    scene.setInitialView();
    scene.onClick((info) => {
      if (info?.kind === "building") selectBuilding(info.id);
      else if (!info) selectBuilding(null);
    });
    scene.onHover((info, pos) => setTooltip(info ? { x: pos.x, y: pos.y, lines: describe(info) } : null));
    setReady(true);
    onReady?.(scene);
    return () => {
      sceneRef.current = null;
      scene.destroy();
    };
  }, []);

  const scene = ready ? sceneRef.current : null;

  useEffect(() => {
    if (scene && regions.data) scene.setRegions(regions.data);
  }, [scene, regions.data]);
  useEffect(() => {
    if (scene && roads.data) scene.setRoads(roads.data);
  }, [scene, roads.data]);
  useEffect(() => {
    if (scene && busRoutes.data) scene.setBusRoutes(busRoutes.data);
  }, [scene, busRoutes.data]);
  useEffect(() => {
    if (scene && buildings.data) scene.setBuildings(buildings.data, useTwin.getState().colorMode);
  }, [scene, buildings.data]);
  useEffect(() => {
    if (scene && facilities.data) scene.setFacilities(facilities.data);
  }, [scene, facilities.data]);
  useEffect(() => {
    if (scene && models.data)
      void scene.setModels(
        models.data,
        async (id) => {
          const token = await accessToken();
          const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};
          return { url: `/api/v1/models/${id}/file`, headers };
        },
        layers.models,
      );
  }, [scene, models.data, layers.models]);

  useEffect(() => {
    scene?.styleBuildings(colorMode, highlight?.ids ?? null, selectedId);
  }, [scene, colorMode, highlight, selectedId, buildings.data]);

  useEffect(() => {
    if (!scene) return;
    scene.setLayerVisible("regions", layers.regions);
    scene.setLayerVisible("roads", layers.roads);
    scene.setLayerVisible("busRoutes", layers.busRoutes);
    scene.setLayerVisible("buildings", layers.buildings);
    scene.setLayerVisible("sensors", layers.sensors);
    scene.setLayerVisible("models", layers.models);
    scene.setFacilityVisibility({ HYDRANT: layers.hydrants, AED: layers.aeds, FIRE_STATION: layers.stations });
    scene.setOsm(layers.osm);
  }, [scene, layers, buildings.data, facilities.data]);

  useEffect(() => {
    scene?.showCoverage(coverage);
  }, [scene, coverage, facilities.data]);
  useEffect(() => {
    scene?.showSites(recommendation);
  }, [scene, recommendation]);
  // 출동 도달권: 기준 시간이 정해지면 서버에서 등시간선 폴리곤을 받아 그린다.
  const isoKey = isochrones?.minutes.join(",");
  useEffect(() => {
    if (!isochrones || isochrones.data) return;
    const ctrl = new AbortController();
    api
      .isochrones(isochrones.minutes, ctrl.signal)
      .then(setIsochroneData)
      .catch((e) => {
        if ((e as Error).name !== "AbortError") toast("error", `도달권을 불러오지 못했습니다: ${(e as Error).message}`);
      });
    return () => ctrl.abort();
  }, [isoKey]);
  useEffect(() => {
    scene?.showIsochrones(isochrones?.data ?? null, layers.isochrones);
  }, [scene, isochrones?.data, layers.isochrones]);
  useEffect(() => {
    if (scene && sensors.length) scene.updateSensors(sensors);
  }, [scene, sensors]);
  useEffect(() => {
    if (scene && camera) scene.flyTo(camera.lon, camera.lat, camera.height);
  }, [scene, camera]);
  useEffect(() => {
    if (scene && highlight && highlight.ids.length > 0) scene.flyToBuildings(highlight.ids);
  }, [scene, highlight]);

  const loading = [regions, roads, buildings, facilities].some((q) => q.isLoading);
  const error = [regions, roads, buildings, facilities].find((q) => q.error)?.error;

  return (
    <div className="map-wrap">
      <div ref={containerRef} className="map" role="region" aria-label="3D 디지털트윈 지도" />
      {loading && <div className="map-status">트윈 데이터 불러오는 중…</div>}
      {error && <div className="map-status error">데이터를 불러오지 못했습니다: {String((error as Error).message)}</div>}
      {tooltip && (
        <div className="map-tooltip" style={{ left: tooltip.x + 14, top: tooltip.y + 14 }} role="tooltip">
          {tooltip.lines.map((l, i) => (
            <div key={i} className={i === 0 ? "tt-title" : "tt-line"}>
              {l}
            </div>
          ))}
        </div>
      )}
      <MapLegend />
      <div className="map-badge">합성(가상) 데이터 · 실제 건물·시설과 무관</div>
    </div>
  );
}

function MapLegend() {
  const colorMode = useTwin((s) => s.colorMode);
  const highlight = useTwin((s) => s.highlight);
  const isochrones = useTwin((s) => s.isochrones);
  const title = { risk: "화재위험도(점)", use: "건물 용도", age: "건축 경과연수" }[colorMode];
  return (
    <div className="map-legend" role="group" aria-label="지도 범례">
      <div className="legend-title">{title}</div>
      <ul>
        {legendFor(colorMode).map((item) => (
          <li key={item.label}>
            <span className="swatch" style={{ background: item.color }} />
            {item.label}
          </li>
        ))}
      </ul>
      <div className="legend-title">시설</div>
      <ul>
        <li><span className="dot" style={{ background: "#e66767" }} />소화전</li>
        <li><span className="dot" style={{ background: "#199e70" }} />AED</li>
        <li><span className="dot big" style={{ background: "#c98500" }} />소방서</li>
        <li><span className="dot ring" />계획(PLANNED)</li>
      </ul>
      {isochrones?.data && (
        <>
          <div className="legend-title">출동 도달권(도로망)</div>
          <ul>
            {isochrones.minutes.map((m) => (
              <li key={m}>
                <span className="swatch" style={{ background: isochroneColor(m, isochrones.minutes) }} />
                {m}분 이내
              </li>
            ))}
          </ul>
        </>
      )}
      {highlight && <div className="legend-note">하이라이트: {highlight.label} ({highlight.ids.length}동)</div>}
    </div>
  );
}

function describe(info: PickInfo): string[] {
  switch (info.kind) {
    case "building": {
      const p = info.props;
      return [
        p.name,
        `${USE_LABELS[p.useType] ?? p.useType} · ${p.floors}층 · ${p.builtYear ?? "-"}년`,
        `화재위험도 ${p.risk ?? "-"}`,
      ];
    }
    case "facility":
      return [info.props.name, `${FACILITY_LABELS[info.props.type]} · ${info.props.status}`];
    case "sensor": {
      const r = info.reading;
      const v = Object.entries(r.values).map(([k, val]) => `${k} ${val}`).join(" · ");
      return [r.code, v, `상태 ${r.status}`];
    }
    case "site":
      return [info.text];
  }
}
