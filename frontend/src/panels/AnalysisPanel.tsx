import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, watchJob } from "../api/client";
import { useRegions } from "../api/hooks";
import type { CoverageResult, FacilityType, RecommendationResult } from "../api/types";
import { FACILITY_LABELS, USE_LABELS } from "../map/colors";
import { useTwin } from "../state/store";

const fmt = (v: number) => v.toLocaleString("ko-KR");

export function AnalysisPanel() {
  const qc = useQueryClient();
  const regions = useRegions();
  const setCoverage = useTwin((s) => s.setCoverage);
  const coverage = useTwin((s) => s.coverage);
  const recommendation = useTwin((s) => s.recommendation);
  const setRecommendation = useTwin((s) => s.setRecommendation);
  const setHighlight = useTwin((s) => s.setHighlight);
  const selectBuilding = useTwin((s) => s.selectBuilding);
  const flyTo = useTwin((s) => s.flyTo);
  const preset = useTwin((s) => s.analysisPreset);

  const [facilityType, setFacilityType] = useState<FacilityType>("HYDRANT");
  const [regionId, setRegionId] = useState<number | null>(null);
  const [customRadius, setCustomRadius] = useState<string>("");
  const [includePlanned, setIncludePlanned] = useState(false);
  const [count, setCount] = useState(3);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [job, setJob] = useState<{ progress: number; message: string } | null>(null);
  const [baseline, setBaseline] = useState<CoverageResult | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const radiusM = customRadius.trim() ? Number(customRadius) : null;

  async function runCoverage(opts?: { facilityType?: FacilityType; regionId?: number | null; radiusM?: number | null; includePlanned?: boolean }) {
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setBusy(true);
    setError(null);
    const req = {
      facilityType: opts?.facilityType ?? facilityType,
      regionId: opts && "regionId" in opts ? opts.regionId : regionId,
      radiusM: opts && "radiusM" in opts ? opts.radiusM : radiusM,
      includePlanned: opts?.includePlanned ?? includePlanned,
      topUncovered: 15,
    };
    try {
      const result = await api.coverage(req, ctrl.signal);
      setCoverage(result);
      setHighlight({ ids: result.uncoveredBuildingIds, label: `${FACILITY_LABELS[result.facilityType]} 미커버 건물` });
      return result;
    } catch (e) {
      if ((e as Error).name !== "AbortError") setError((e as Error).message);
      return null;
    } finally {
      if (abortRef.current === ctrl) setBusy(false);
    }
  }

  // 대시보드·AI 코파일럿에서 넘어온 분석 요청을 반영해 바로 실행한다.
  useEffect(() => {
    if (!preset) return;
    setFacilityType(preset.facilityType);
    setRegionId(preset.regionId);
    setCustomRadius(preset.radiusM ? String(preset.radiusM) : "");
    void runCoverage({ facilityType: preset.facilityType, regionId: preset.regionId, radiusM: preset.radiusM });
  }, [preset?.key]);

  async function recommend() {
    if (facilityType === "FIRE_STATION") return;
    setError(null);
    setRecommendation(null);
    setJob({ progress: 0, message: "Job 생성" });
    try {
      const created = await api.createJob({ type: "RECOMMEND_SITES", facilityType, count, radiusM, regionId });
      watchJob<RecommendationResult>(created.id, {
        progress: (progress, message) => setJob({ progress, message }),
        done: (j) => {
          setJob(null);
          if (j.result) {
            setRecommendation(j.result);
            const ids = j.result.sites.flatMap((s) => s.buildingIds);
            setHighlight({ ids, label: "추천 입지로 새로 커버되는 건물" });
          }
        },
        failed: (j) => {
          setJob(null);
          setError(j.error ?? "추천 실패");
        },
      });
    } catch (e) {
      setJob(null);
      setError((e as Error).message);
    }
  }

  async function applySites() {
    if (!recommendation) return;
    setBusy(true);
    const before = await api.coverage({ facilityType: recommendation.facilityType, regionId, radiusM, includePlanned: true, includeBlindSpots: false });
    setBaseline(before);
    await Promise.all(
      recommendation.sites.map((s) =>
        api.addPlanned({ type: recommendation.facilityType, lon: s.lon, lat: s.lat, name: `계획 ${FACILITY_LABELS[recommendation.facilityType]} #${s.rank}` }),
      ),
    );
    await qc.invalidateQueries({ queryKey: ["facilities"] });
    setIncludePlanned(true);
    setRecommendation(null);
    await runCoverage({ facilityType: recommendation.facilityType, includePlanned: true });
    void qc.invalidateQueries({ queryKey: ["stats"] });
  }

  async function clearPlanned() {
    await api.clearPlanned();
    await qc.invalidateQueries({ queryKey: ["facilities"] });
    setBaseline(null);
    setIncludePlanned(false);
    await runCoverage({ includePlanned: false });
  }

  return (
    <div className="analysis">
      <section className="form-grid" aria-label="분석 조건">
        <label>
          시설 유형
          <select value={facilityType} onChange={(e) => setFacilityType(e.target.value as FacilityType)}>
            <option value="HYDRANT">소화전</option>
            <option value="AED">AED</option>
            <option value="FIRE_STATION">소방서(골든타임)</option>
          </select>
        </label>
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
          반경(m)
          <input
            type="number"
            min={10}
            max={5000}
            placeholder="기준값"
            value={customRadius}
            onChange={(e) => setCustomRadius(e.target.value)}
          />
        </label>
        <label className="check">
          <input type="checkbox" checked={includePlanned} onChange={(e) => setIncludePlanned(e.target.checked)} />
          계획 시설 포함
        </label>
      </section>
      <div className="btn-row">
        <button type="button" className="btn primary" onClick={() => void runCoverage()} disabled={busy}>
          {busy ? "분석 중…" : "커버리지 분석"}
        </button>
        {coverage && (
          <button
            type="button"
            className="btn"
            onClick={() => {
              setCoverage(null);
              setHighlight(null);
              setBaseline(null);
            }}
          >
            지우기
          </button>
        )}
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}

      {coverage && (
        <section className="result" aria-label="커버리지 결과">
          <div className="tiles">
            <div className="tile">
              <div className="tile-label">커버리지</div>
              <div className="tile-value">{coverage.summary.coverageRate}%</div>
              {baseline && (
                <div className="tile-delta">
                  계획 반영 전 {baseline.summary.coverageRate}% → +
                  {(coverage.summary.coverageRate - baseline.summary.coverageRate).toFixed(1)}%p
                </div>
              )}
            </div>
            <div className="tile">
              <div className="tile-label">미커버 건물</div>
              <div className="tile-value">{fmt(coverage.summary.totalBuildings - coverage.summary.coveredBuildings)}동</div>
            </div>
            <div className="tile">
              <div className="tile-label">미커버 고위험</div>
              <div className="tile-value">{fmt(coverage.summary.uncoveredHighRisk)}동</div>
            </div>
            <div className="tile">
              <div className="tile-label">사각지대 면적</div>
              <div className="tile-value">{(coverage.summary.blindSpotAreaM2 / 10_000).toFixed(1)}ha</div>
            </div>
          </div>
          <p className="muted small">
            기준: {coverage.standard} · {coverage.elapsedMs}ms
          </p>
          <table className="data-table">
            <thead>
              <tr>
                <th>행정동</th>
                <th className="num">반경</th>
                <th className="num">커버리지</th>
                <th className="num">미커버 고위험</th>
              </tr>
            </thead>
            <tbody>
              {coverage.regions.map((r) => (
                <tr key={r.regionId}>
                  <td>{r.regionName}</td>
                  <td className="num">{r.radiusM.toFixed(0)}m</td>
                  <td className="num">{r.coverageRate}%</td>
                  <td className="num">{fmt(r.uncoveredHighRisk)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h3 className="sub">우선 조치 대상 (위험도순)</h3>
          <ol className="item-list">
            {coverage.topUncovered.map((b) => (
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
                    위험도 {b.risk} · {USE_LABELS[b.useType] ?? b.useType} · {b.builtYear ?? "-"}년 · 최근접 {b.nearestFacilityM}m
                  </span>
                </button>
              </li>
            ))}
          </ol>
        </section>
      )}

      {facilityType !== "FIRE_STATION" && (
        <section className="recommend" aria-label="최적 입지 추천">
          <h3 className="sub">최적 입지 추천 (MCLP)</h3>
          <div className="btn-row">
            <label className="inline">
              추가 설치 수
              <input type="number" min={1} max={10} value={count} onChange={(e) => setCount(Math.max(1, Math.min(10, Number(e.target.value))))} />
            </label>
            <button type="button" className="btn primary" onClick={() => void recommend()} disabled={!!job}>
              추천 실행
            </button>
          </div>
          {job && (
            <div className="progress" role="progressbar" aria-valuenow={job.progress} aria-valuemin={0} aria-valuemax={100}>
              <div className="progress-bar" style={{ width: `${job.progress}%` }} />
              <span>
                {job.progress}% · {job.message}
              </span>
            </div>
          )}
          {recommendation && (
            <div className="result">
              <p>
                커버리지 <strong>{recommendation.coverageBefore}%</strong> → <strong>{recommendation.coverageAfter}%</strong> (신규{" "}
                {fmt(recommendation.newlyCovered)}동, 후보 {fmt(recommendation.candidateCount)}개, {recommendation.elapsedMs}ms)
              </p>
              <ol className="item-list">
                {recommendation.sites.map((s) => (
                  <li key={s.rank}>
                    <button type="button" className="item" onClick={() => flyTo(s.lon, s.lat, 450)}>
                      <span className="item-title">
                        추천 {s.rank} · {s.regionName}
                      </span>
                      <span className="item-meta">
                        신규 커버 {s.newlyCoveredBuildings}동 · 위험도 합 {s.riskCovered} · 반경 {s.radiusM}m
                      </span>
                    </button>
                  </li>
                ))}
              </ol>
              <p className="muted small">{recommendation.method}</p>
              <div className="btn-row">
                <button type="button" className="btn primary" onClick={() => void applySites()} disabled={busy}>
                  계획 시설로 배치하고 재분석
                </button>
              </div>
            </div>
          )}
          <button type="button" className="link-btn" onClick={() => void clearPlanned()}>
            계획(PLANNED) 시설 모두 지우기
          </button>
        </section>
      )}
    </div>
  );
}
