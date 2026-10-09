// AI 에이전트가 돌려준 지도 액션(ui_action 이벤트)을 앱 상태로 옮긴다.
// 에이전트(ai-agent/app/ui_actions.py)와 같은 스키마를 쓴다.
import type { ColorMode, FacilityType, RecommendationResult } from "../api/types";
import type { LayerKey } from "../state/store";

export type UiAction =
  | { type: "highlightBuildings"; ids: number[]; label: string }
  | { type: "flyTo"; lon: number; lat: number; height?: number }
  | { type: "selectBuilding"; id: number }
  | { type: "runCoverage"; facilityType: FacilityType; regionId?: number | null; radiusM?: number | null }
  | { type: "showSites"; result: RecommendationResult }
  | { type: "setColorMode"; mode: ColorMode }
  | { type: "showLayer"; layer: LayerKey; visible: boolean }
  | { type: "showIsochrones"; minutes: number[] };

export interface ActionTarget {
  setHighlight: (h: { ids: number[]; label: string } | null) => void;
  flyTo: (lon: number, lat: number, height?: number) => void;
  selectBuilding: (id: number | null) => void;
  presetAnalysis: (p: { facilityType: FacilityType; regionId: number | null; radiusM: number | null }) => void;
  setRecommendation: (r: RecommendationResult | null) => void;
  setColorMode: (m: ColorMode) => void;
  setLayer: (k: LayerKey, v: boolean) => void;
  showIsochrones: (minutes: number[] | null) => void;
}

const LAYER_KEYS: LayerKey[] = [
  "buildings",
  "regions",
  "roads",
  "hydrants",
  "aeds",
  "stations",
  "sensors",
  "busRoutes",
  "models",
  "isochrones",
  "osm",
];

/** 알 수 없거나 형식이 틀린 액션은 무시하고 false를 돌려준다(에이전트 출력은 신뢰하지 않는다). */
export function applyUiAction(action: unknown, t: ActionTarget): boolean {
  if (!action || typeof action !== "object") return false;
  const a = action as Record<string, unknown>;
  const num = (v: unknown) => typeof v === "number" && Number.isFinite(v);
  switch (a.type) {
    case "highlightBuildings":
      if (!Array.isArray(a.ids) || !a.ids.every(num)) return false;
      t.setHighlight({ ids: (a.ids as number[]).slice(0, 5000), label: String(a.label ?? "AI 결과") });
      return true;
    case "flyTo":
      if (!num(a.lon) || !num(a.lat)) return false;
      t.flyTo(a.lon as number, a.lat as number, num(a.height) ? (a.height as number) : undefined);
      return true;
    case "selectBuilding":
      if (!num(a.id)) return false;
      t.selectBuilding(a.id as number);
      return true;
    case "runCoverage":
      if (!["HYDRANT", "AED", "FIRE_STATION"].includes(a.facilityType as string)) return false;
      t.presetAnalysis({
        facilityType: a.facilityType as FacilityType,
        regionId: num(a.regionId) ? (a.regionId as number) : null,
        radiusM: num(a.radiusM) ? (a.radiusM as number) : null,
      });
      return true;
    case "showSites": {
      const r = a.result as RecommendationResult | undefined;
      if (!r || !Array.isArray(r.sites)) return false;
      t.setRecommendation(r);
      return true;
    }
    case "setColorMode":
      if (!["risk", "use", "age"].includes(a.mode as string)) return false;
      t.setColorMode(a.mode as ColorMode);
      return true;
    case "showLayer":
      if (!LAYER_KEYS.includes(a.layer as LayerKey)) return false;
      t.setLayer(a.layer as LayerKey, Boolean(a.visible));
      return true;
    case "showIsochrones": {
      if (!Array.isArray(a.minutes) || a.minutes.length === 0 || a.minutes.length > 5) return false;
      if (!a.minutes.every((m) => num(m) && (m as number) >= 1 && (m as number) <= 30)) return false;
      t.showIsochrones([...new Set(a.minutes as number[])].sort((x, y) => x - y));
      return true;
    }
    default:
      return false;
  }
}

export function describeAction(action: UiAction): string {
  switch (action.type) {
    case "highlightBuildings":
      return `건물 ${action.ids.length}동 하이라이트`;
    case "flyTo":
      return "카메라 이동";
    case "selectBuilding":
      return `건물 #${action.id} 선택`;
    case "runCoverage":
      return `${action.facilityType} 커버리지 분석 표시`;
    case "showSites":
      return `추천 입지 ${action.result.sites.length}곳 표시`;
    case "setColorMode":
      return `색상 기준: ${action.mode}`;
    case "showLayer":
      return `레이어 ${action.layer} ${action.visible ? "켜기" : "끄기"}`;
    case "showIsochrones":
      return `출동 도달권 ${action.minutes.join("·")}분 표시`;
  }
}
