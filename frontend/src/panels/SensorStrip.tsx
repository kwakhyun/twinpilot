import type { SensorReading } from "../api/types";
import { useTwin } from "../state/store";

/** 센서 경보 요약. 상태는 색만으로 구분하지 않고 아이콘·문구를 함께 쓴다. */
export function SensorStrip({ readings, connected }: { readings: SensorReading[]; connected: boolean }) {
  const flyTo = useTwin((s) => s.flyTo);
  const alerts = readings.filter((r) => r.status === "ALERT");
  const badAir = readings.filter((r) => r.status === "BAD" || r.status === "VERY_BAD");
  const air = readings.filter((r) => r.kind === "AIR_QUALITY");
  const avgPm = air.length ? Math.round(air.reduce((s, r) => s + (r.values.pm25 ?? 0), 0) / air.length) : null;

  return (
    <div className="sensor-strip" role="status">
      <span className={`live ${connected ? "on" : ""}`}>{connected ? "● 실시간" : "○ 연결 중"}</span>
      <span>센서 {readings.length}개</span>
      {alerts.length > 0 ? (
        alerts.slice(0, 3).map((a) => (
          <button key={a.sensorId} type="button" className="alert critical" onClick={() => flyTo(a.lon, a.lat, 250)}>
            ⚠ {a.code} 저수압 {a.values.pressureMpa}MPa
          </button>
        ))
      ) : (
        <span className="alert good">✓ 수압 경보 없음</span>
      )}
      {avgPm != null && (
        <span>
          버스 이동형 PM2.5 평균 {avgPm}㎍/㎥{badAir.length > 0 && <strong className="warn-text"> · 나쁨 {badAir.length}대</strong>}
        </span>
      )}
    </div>
  );
}
