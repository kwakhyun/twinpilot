import type { ColorMode } from "../api/types";
import { useTwin, type LayerKey } from "../state/store";

const LAYERS: { key: LayerKey; label: string; note?: string }[] = [
  { key: "buildings", label: "건물(3D)" },
  { key: "regions", label: "행정동 경계" },
  { key: "roads", label: "도로" },
  { key: "hydrants", label: "소화전" },
  { key: "aeds", label: "AED" },
  { key: "stations", label: "소방서" },
  { key: "sensors", label: "IoT 센서(경보·이동형)" },
  { key: "busRoutes", label: "버스 노선(이동형 센서)" },
  { key: "models", label: "3D 모델(glTF)" },
  { key: "osm", label: "OSM 배경지도", note: "합성 데이터라 실제 지도와 맞지 않습니다" },
];

const MODES: { key: ColorMode; label: string }[] = [
  { key: "risk", label: "화재위험도" },
  { key: "use", label: "용도" },
  { key: "age", label: "노후도" },
];

export function LayerPanel() {
  const layers = useTwin((s) => s.layers);
  const toggleLayer = useTwin((s) => s.toggleLayer);
  const colorMode = useTwin((s) => s.colorMode);
  const setColorMode = useTwin((s) => s.setColorMode);
  const highlight = useTwin((s) => s.highlight);
  const setHighlight = useTwin((s) => s.setHighlight);

  return (
    <div className="layers">
      <fieldset>
        <legend>건물 색상</legend>
        <div className="segmented" role="radiogroup" aria-label="건물 색상 기준">
          {MODES.map((m) => (
            <button
              key={m.key}
              type="button"
              role="radio"
              aria-checked={colorMode === m.key}
              className={colorMode === m.key ? "active" : ""}
              onClick={() => setColorMode(m.key)}
            >
              {m.label}
            </button>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend>레이어</legend>
        {LAYERS.map((l) => (
          <label key={l.key} className="check">
            <input type="checkbox" checked={layers[l.key]} onChange={() => toggleLayer(l.key)} />
            <span>
              {l.label}
              {l.note && <small className="muted"> · {l.note}</small>}
            </span>
          </label>
        ))}
      </fieldset>
      {highlight && (
        <div className="note">
          하이라이트: {highlight.label} ({highlight.ids.length}동)
          <button type="button" className="link-btn" onClick={() => setHighlight(null)}>
            해제
          </button>
        </div>
      )}
      <p className="muted small">
        건물을 클릭하면 오른쪽에 상세 정보가 열립니다. 위험도는 노후도·구조·층수·최근접 소화전 거리·소방서 도달시간으로
        계산합니다.
      </p>
    </div>
  );
}
