import { useState } from "react";
import { api } from "../api/client";
import { useRegions } from "../api/hooks";
import type { ResponseResult } from "../api/types";
import { USE_LABELS } from "../map/colors";
import { useTwin } from "../state/store";

const TARGETS = [4, 5, 7, 10];

/** 도로망 최단경로 기준 소방서 출동 시간 분석과 도달권(등시간선) 표시. */
export function ResponseSection() {
  const regions = useRegions();
  const setHighlight = useTwin((s) => s.setHighlight);
  const showIsochrones = useTwin((s) => s.showIsochrones);
  const isochrones = useTwin((s) => s.isochrones);
  const selectBuilding = useTwin((s) => s.selectBuilding);
  const flyTo = useTwin((s) => s.flyTo);
  const [regionId, setRegionId] = useState<number | null>(null);
  const [target, setTarget] = useState(7);
  const [result, setResult] = useState<ResponseResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const r = await api.response({ regionId, targetMin: target, top: 10 });
      setResult(r);
      setHighlight({ ids: r.beyondTargetIds, label: `출동 ${target}분 초과 건물` });
      showIsochrones([...new Set([3, 5, target])].sort((a, b) => a - b));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const s = result?.summary;
  return (
    <section className="response" aria-label="출동 시간 분석">
      <h3 className="sub">소방서 출동 시간 (도로망)</h3>
      <div className="form-grid">
        <label>
          행정동
          <select value={regionId ?? ""} onChange={(e) => setRegionId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">전체</option>
            {regions.data?.features.map((f) => (
              <option key={f.id} value={f.id}>
                {f.properties.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          목표 시간
          <select value={target} onChange={(e) => setTarget(Number(e.target.value))}>
            {TARGETS.map((t) => (
              <option key={t} value={t}>
                {t}분
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="btn-row">
        <button type="button" className="btn primary" onClick={() => void run()} disabled={busy}>
          {busy ? "분석 중…" : "출동 시간 분석"}
        </button>
        {isochrones && (
          <button type="button" className="btn" onClick={() => showIsochrones(null)}>
            도달권 지우기
          </button>
        )}
      </div>
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
      {result && s && (
        <div className="result">
          <div className="tiles">
            <div className="tile">
              <div className="tile-label">{result.targetMin}분 내 도달</div>
              <div className="tile-value">{s.withinRate}%</div>
            </div>
            <div className="tile">
              <div className="tile-label">초과 건물</div>
              <div className="tile-value">{s.beyondTarget.toLocaleString("ko-KR")}동</div>
            </div>
            <div className="tile">
              <div className="tile-label">초과 고위험</div>
              <div className="tile-value">{s.highRiskBeyond}동</div>
            </div>
            <div className="tile">
              <div className="tile-label">90백분위</div>
              <div className="tile-value">{s.p90Min ?? "-"}분</div>
            </div>
          </div>
          {s.unreachable > 0 && <p className="error-text">도로망으로 도달할 수 없는 건물 {s.unreachable}동</p>}
          {s.farFromRoad > 0 && <p className="muted small">도로에서 50m 넘게 떨어진 건물 {s.farFromRoad}동(소방차 접근 검토)</p>}
          <table className="data-table">
            <thead>
              <tr>
                <th>행정동</th>
                <th className="num">도달률</th>
                <th className="num">90백분위</th>
              </tr>
            </thead>
            <tbody>
              {result.regions.map((r) => (
                <tr key={r.regionId}>
                  <td>{r.regionName}</td>
                  <td className="num">{r.withinRate}%</td>
                  <td className="num">{r.p90Min ?? "-"}분</td>
                </tr>
              ))}
            </tbody>
          </table>
          {result.slowest.length > 0 && (
            <>
              <h4 className="sub">목표 초과 · 위험도 높은 순</h4>
              <ol className="item-list">
                {result.slowest.map((b) => (
                  <li key={b.id}>
                    <button
                      type="button"
                      className="item"
                      onClick={() => {
                        selectBuilding(b.id);
                        flyTo(b.lon, b.lat, 350);
                      }}
                    >
                      <span className="item-title">{b.name}</span>
                      <span className="item-meta">
                        출동 {b.responseMin}분 · 위험도 {b.risk ?? "-"} · {USE_LABELS[b.useType] ?? b.useType} · {b.stationName}
                      </span>
                    </button>
                  </li>
                ))}
              </ol>
            </>
          )}
          <p className="muted small">{result.method}</p>
        </div>
      )}
    </section>
  );
}
