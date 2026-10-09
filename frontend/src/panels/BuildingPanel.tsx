import { lazy, Suspense, useState } from "react";
import { useBuilding } from "../api/hooks";
import { riskColor, STATUS, STRUCTURE_LABELS, USE_LABELS } from "../map/colors";
import { useTwin } from "../state/store";

const BuildingSection = lazy(() => import("../three/BuildingSection"));

const FACTORS: { key: "age" | "structure" | "height" | "hydrant" | "response"; label: string; max: number }[] = [
  { key: "age", label: "노후도", max: 30 },
  { key: "structure", label: "구조", max: 20 },
  { key: "height", label: "층수", max: 15 },
  { key: "hydrant", label: "소화전 거리", max: 20 },
  { key: "response", label: "소방서 출동", max: 15 },
];

export function BuildingPanel() {
  const selectedId = useTwin((s) => s.selectedBuildingId);
  const selectBuilding = useTwin((s) => s.selectBuilding);
  const askCopilot = useTwin((s) => s.askCopilot);
  const flyTo = useTwin((s) => s.flyTo);
  const { data, isLoading, error } = useBuilding(selectedId);
  const [show3d, setShow3d] = useState(true);

  if (selectedId == null) return null;

  return (
    <aside className="detail" aria-label="건물 상세">
      <header className="detail-head">
        <h2>{data?.building.name ?? `건물 #${selectedId}`}</h2>
        <button type="button" className="icon-btn" aria-label="닫기" onClick={() => selectBuilding(null)}>
          ×
        </button>
      </header>
      {isLoading && <p className="muted">불러오는 중…</p>}
      {error && <p className="error-text">{(error as Error).message}</p>}
      {data && (
        <div className="detail-body">
          <div className="chips">
            <span className="chip">{USE_LABELS[data.building.useType] ?? data.building.useType}</span>
            <span className="chip">{STRUCTURE_LABELS[data.building.structure] ?? data.building.structure}</span>
            <span className="chip">
              지상 {data.building.floors}층{data.basementFloors > 0 && ` / 지하 ${data.basementFloors}층`}
            </span>
            <span className="chip">
              {data.building.builtYear ?? "-"}년 ({data.building.age ?? "-"}년 경과)
            </span>
            <span className="chip">{data.building.regionName}</span>
          </div>

          <section>
            <div className="risk-head">
              <span className="risk-swatch" style={{ background: riskColor(data.building.risk) }} />
              <span className="risk-score">{data.building.risk ?? "-"}</span>
              <span className="muted">/ 100 화재위험도</span>
            </div>
            <ul className="meters">
              {FACTORS.map((f) => {
                const v = data.building.riskFactors?.[f.key] ?? 0;
                return (
                  <li key={f.key}>
                    <span className="meter-label">{f.label}</span>
                    <span className="meter" aria-hidden>
                      <span className="meter-fill" style={{ width: `${(v / f.max) * 100}%` }} />
                    </span>
                    <span className="meter-value">
                      {v} / {f.max}
                    </span>
                  </li>
                );
              })}
            </ul>
          </section>

          <section>
            <h3 className="sub">출동·진압 여건</h3>
            {data.response && (
              <p>
                <StatusMark ok={data.response.withinTarget} />
                {data.response.stationName} 출동 <strong>{data.response.estimatedMinutes}분</strong> (목표{" "}
                {data.response.targetMinutes}분, 직선 {Math.round(data.response.straightDistanceM)}m · 직선 근사{" "}
                {data.response.straightLineMinutes}분)
                <br />
                <small className="muted">
                  {data.response.method}
                  {data.response.roadAccessM != null && ` · 가장 가까운 도로까지 ${Math.round(data.response.roadAccessM)}m`}
                </small>
              </p>
            )}
            <p>
              <StatusMark ok={data.ladder.floorsBeyondReach === 0} />
              사다리차 유효 높이 {data.ladder.ladderReachM}m 기준{" "}
              {data.ladder.floorsBeyondReach > 0 ? (
                <>
                  <strong>{data.ladder.reachableFloor + 1}층 이상 {data.ladder.floorsBeyondReach}개 층</strong> 외부 진입 곤란
                </>
              ) : (
                "전 층 도달 가능"
              )}
            </p>
            <button type="button" className="link-btn" onClick={() => setShow3d((v) => !v)}>
              {show3d ? "단면 3D 숨기기" : "단면 3D 보기"}
            </button>
            {show3d && (
              <Suspense fallback={<div className="three-view loading">3D 엔진 불러오는 중…</div>}>
                <BuildingSection
                  footprint={data.footprint}
                  floors={data.building.floors}
                  basementFloors={data.basementFloors}
                  floorHeightM={data.ladder.floorHeightM}
                  ladderReachM={data.ladder.ladderReachM}
                />
                <p className="legend-inline">
                  <span className="swatch" style={{ background: "#6da7ec" }} />
                  도달 가능 층
                  <span className="swatch" style={{ background: STATUS.critical }} />
                  도달 불가 층
                  <span className="swatch" style={{ background: "#4b4b47" }} />
                  지하층
                </p>
              </Suspense>
            )}
          </section>

          <section>
            <h3 className="sub">인근 소화전</h3>
            <ul className="facility-list">
              {data.nearestHydrants.map((h) => {
                const sensor = data.hydrantSensors.find((s) => s.facilityId === h.id);
                return (
                  <li key={h.id}>
                    <button type="button" className="link-btn" onClick={() => flyTo(h.lon, h.lat, 250)}>
                      {h.name}
                    </button>
                    <span className="num">{h.distanceM}m</span>
                    {h.status !== "ACTIVE" && <span className="chip warn">{h.status}</span>}
                    {sensor && (
                      <span className={`chip ${sensor.status === "ALERT" ? "critical" : ""}`}>
                        {sensor.status === "ALERT" ? "⚠ 저수압 " : "수압 "}
                        {sensor.values.pressureMpa}MPa
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
            <h3 className="sub">인근 AED</h3>
            <ul className="facility-list">
              {data.nearestAeds.map((a) => (
                <li key={a.id}>
                  <span>{a.name}</span>
                  <span className="num">{a.distanceM}m</span>
                </li>
              ))}
            </ul>
          </section>
          <button
            type="button"
            className="btn"
            onClick={() => askCopilot(`건물 ${data.building.id}번(${data.building.name})의 화재 대응 브리핑을 해줘`)}
          >
            AI 코파일럿에게 브리핑 요청
          </button>
          <p className="muted small">병렬 조회 {data.queryMillis}ms</p>
        </div>
      )}
    </aside>
  );
}

function StatusMark({ ok }: { ok: boolean }) {
  return (
    <span className={`status-mark ${ok ? "good" : "critical"}`} role="img" aria-label={ok ? "기준 충족" : "기준 미달"}>
      {ok ? "✓ 충족" : "! 미달"}
    </span>
  );
}
