import { useStats } from "../api/hooks";
import { BarChart } from "../charts/BarChart";
import { RISK_RAMP, USE_LABELS } from "../map/colors";
import { useTwin } from "../state/store";

const fmt = (v: number) => v.toLocaleString("ko-KR");

/** 대시보드: 핵심 지표(수치)는 스탯 타일, 분포는 단일 계열 막대, 행정동 비교는 표. */
export function DashboardPanel() {
  const { data, isLoading, error, isFetching } = useStats();
  const presetAnalysis = useTwin((s) => s.presetAnalysis);
  if (isLoading) return <p className="muted">집계 중…</p>;
  if (error || !data) return <p className="error-text">통계를 불러오지 못했습니다.</p>;

  const riskBins = data.riskHistogram.map((b) => {
    const lower = Number(b.key);
    return { key: b.key, label: `${lower}`, value: b.count, color: RISK_RAMP[Math.min(4, Math.floor(lower / 20))] };
  });
  const maxRiskBin = riskBins.reduce((a, b) => (b.value > a.value ? b : a), riskBins[0]);
  const decades = data.builtDecades.map((b) => ({ key: b.key, label: b.key.replace("s", ""), value: b.count }));
  const uses = data.useTypes.map((b) => ({ key: b.key, label: USE_LABELS[b.key] ?? b.key, value: b.count }));

  return (
    <div className={`dashboard ${isFetching ? "refetching" : ""}`}>
      <div className="hero">
        <div className="hero-label">소화전 법정 기준 커버리지</div>
        <div className="hero-value">{data.hydrantCoverage.coverageRate}%</div>
        <div className="hero-sub">
          {fmt(data.hydrantCoverage.coveredBuildings)} / {fmt(data.hydrantCoverage.totalBuildings)}동 · 미커버 고위험{" "}
          {fmt(data.hydrantCoverage.uncoveredHighRisk)}동
        </div>
      </div>
      <div className="tiles">
        <StatTile label="AED 커버리지(300m)" value={`${data.aedCoverage.coverageRate}%`} />
        <StatTile label={`고위험 건물(≥${data.highRiskThreshold}점)`} value={`${fmt(data.totals.highRisk)}동`} />
        <StatTile label={`${data.oldAgeYears}년 이상 노후`} value={`${data.totals.oldRatio}%`} />
        <StatTile label="평균 화재위험도" value={`${data.totals.avgRisk}`} />
      </div>

      <BarChart title="화재위험도 분포 (건물 수, 10점 구간)" data={riskBins} unit="동" labelKeys={maxRiskBin ? [maxRiskBin.key] : []} />
      <BarChart title="준공 연대별 건물 수" data={decades} unit="동" />
      <BarChart title="용도별 건물 수" data={uses} unit="동" orientation="horizontal" />

      <figure className="chart">
        <figcaption>
          <span>행정동별 현황</span>
        </figcaption>
        <table className="data-table">
          <thead>
            <tr>
              <th>행정동</th>
              <th className="num">건물</th>
              <th className="num">평균 위험</th>
              <th className="num">고위험</th>
              <th className="num">노후 비율</th>
            </tr>
          </thead>
          <tbody>
            {data.regions.map((r) => (
              <tr key={r.regionId}>
                <td>
                  <button
                    type="button"
                    className="link-btn"
                    onClick={() => presetAnalysis({ facilityType: "HYDRANT", regionId: r.regionId, radiusM: null })}
                    title="이 행정동으로 커버리지 분석 열기"
                  >
                    {r.name}
                  </button>
                </td>
                <td className="num">{fmt(r.buildings)}</td>
                <td className="num">{r.avgRisk}</td>
                <td className="num">{fmt(r.highRisk)}</td>
                <td className="num">{r.oldRatio}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </figure>
    </div>
  );
}

function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="tile">
      <div className="tile-label">{label}</div>
      <div className="tile-value">{value}</div>
    </div>
  );
}
