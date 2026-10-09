import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, watchJob } from "../api/client";
import type { ImportLayer, ImportResult, JobView } from "../api/types";
import { useTwin } from "../state/store";

const LAYERS: { key: ImportLayer; label: string }[] = [
  { key: "BUILDING", label: "건물(폴리곤)" },
  { key: "HYDRANT", label: "소화전(점)" },
  { key: "AED", label: "AED(점)" },
  { key: "FIRE_STATION", label: "소방서(점)" },
  { key: "ROAD", label: "도로 중심선(선)" },
  { key: "REGION", label: "행정구역(폴리곤)" },
];
const SRIDS = [4326, 5186, 5179, 5174, 5181, 5185, 5187, 5188, 3857];
const MAPPING_EXAMPLE = `{
  "fields": {"externalId": "원본ID필드", "name": "건물명필드", "useType": "주용도필드",
             "structure": "구조필드", "floors": "지상층수필드", "builtYear": "사용승인일필드"},
  "values": {"useType": {"공동주택": "RESIDENTIAL_APT"}, "structure": {"철근콘크리트구조": "RC"}},
  "floorHeightM": 3.0
}`;

type Running = { label: string; progress: number; message: string };

/** 관리자: 외부 GeoJSON 적재(검증 → 반영), 도로망·위험도 재계산, 감사 로그. */
export function AdminPanel() {
  const qc = useQueryClient();
  const toast = useTwin((s) => s.toast);
  const [file, setFile] = useState<File | null>(null);
  const [layer, setLayer] = useState<ImportLayer>("BUILDING");
  const [source, setSource] = useState("");
  const [srid, setSrid] = useState<number | "">("");
  const [mode, setMode] = useState<"UPSERT" | "REPLACE">("UPSERT");
  const [mapping, setMapping] = useState("");
  const [dryRun, setDryRun] = useState(true);
  const [running, setRunning] = useState<Running | null>(null);
  const [report, setReport] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [auditAction, setAuditAction] = useState("");

  const imports = useQuery({ queryKey: ["imports"], queryFn: api.imports, refetchInterval: running ? 3000 : false });
  const audit = useQuery({ queryKey: ["audit", auditAction], queryFn: () => api.audit({ action: auditAction || undefined, limit: 50 }) });

  function follow<R>(job: JobView<R>, label: string, onDone: (r: R | null) => void) {
    setRunning({ label, progress: job.progress, message: job.message });
    watchJob<R>(job.id, {
      progress: (progress, message) => setRunning({ label, progress, message }),
      done: (j) => {
        setRunning(null);
        onDone(j.result);
        void qc.invalidateQueries();
      },
      failed: (j) => {
        setRunning(null);
        setError(j.error ?? `${label} 실패`);
        void qc.invalidateQueries({ queryKey: ["imports"] });
      },
    });
  }

  async function submitImport() {
    if (!file || !source) return;
    setError(null);
    setReport(null);
    if (mapping.trim()) {
      try {
        JSON.parse(mapping);
      } catch {
        setError("매핑이 올바른 JSON이 아닙니다.");
        return;
      }
    }
    const form = new FormData();
    form.append("file", file);
    form.append("layer", layer);
    form.append("source", source);
    form.append("mode", mode);
    form.append("dryRun", String(dryRun));
    if (srid) form.append("srid", String(srid));
    if (mapping.trim()) form.append("mapping", mapping);
    try {
      const job = await api.importData(form);
      follow<ImportResult>(job, dryRun ? "검증(dry run)" : "적재", (r) => {
        setReport(r);
        if (r && !r.dryRun) toast("success", `적재 완료: 신규 ${r.inserted}, 갱신 ${r.updated}`);
      });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function refreshRisk() {
    setError(null);
    try {
      const job = await api.createJob<{ updatedBuildings: number }>({ type: "REFRESH_RISK" });
      follow(job, "도로망·위험도 재계산", (r) => toast("success", `건물 ${r?.updatedBuildings ?? 0}동 재계산 완료`));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="admin">
      <section aria-label="데이터 적재">
        <h2 className="sub">GeoJSON 데이터 적재</h2>
        <form
          className="form-grid"
          onSubmit={(e) => {
            e.preventDefault();
            void submitImport();
          }}
        >
          <label className="span2">
            파일(.geojson)
            <input type="file" accept=".geojson,.json,application/geo+json" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </label>
          <label>
            레이어
            <select value={layer} onChange={(e) => setLayer(e.target.value as ImportLayer)}>
              {LAYERS.map((l) => (
                <option key={l.key} value={l.key}>
                  {l.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            출처 이름
            <input value={source} onChange={(e) => setSource(e.target.value.toLowerCase())} placeholder="예: building-2026q3" pattern="[a-z0-9][a-z0-9._\-]{0,63}" required />
          </label>
          <label>
            좌표계(EPSG)
            <select value={srid} onChange={(e) => setSrid(e.target.value ? Number(e.target.value) : "")}>
              <option value="">파일 crs 또는 4326</option>
              {SRIDS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <label>
            방식
            <select value={mode} onChange={(e) => setMode(e.target.value as "UPSERT" | "REPLACE")}>
              <option value="UPSERT">추가·갱신(UPSERT)</option>
              <option value="REPLACE" disabled={layer === "REGION"}>
                교체(REPLACE)
              </option>
            </select>
          </label>
          <label className="span2">
            속성 매핑(JSON, 원본 데이터 명세서를 보고 작성)
            <textarea rows={5} value={mapping} onChange={(e) => setMapping(e.target.value)} placeholder={MAPPING_EXAMPLE} spellCheck={false} />
          </label>
          <label className="check span2">
            <input type="checkbox" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)} />
            검증만 하기(dry run) — 반영 전에 오류·샘플을 확인합니다
          </label>
          <div className="btn-row span2">
            <button type="submit" className="btn primary" disabled={!file || !source || !!running}>
              {dryRun ? "검증 실행" : "적재 실행"}
            </button>
            <button type="button" className="btn" onClick={() => void refreshRisk()} disabled={!!running}>
              도로망·위험도 재계산
            </button>
          </div>
        </form>
        {running && (
          <div className="progress" role="progressbar" aria-valuenow={running.progress} aria-valuemin={0} aria-valuemax={100} aria-label={running.label}>
            <div className="progress-bar" style={{ width: `${running.progress}%` }} />
            <span>
              {running.label} {running.progress}% · {running.message}
            </span>
          </div>
        )}
        {error && (
          <p className="error-text" role="alert">
            {error}
          </p>
        )}
        {report && <ImportReport r={report} />}
      </section>

      <section aria-label="최근 적재">
        <h2 className="sub">최근 적재 Job</h2>
        <table className="data-table">
          <thead>
            <tr>
              <th>시각</th>
              <th>레이어·출처</th>
              <th>상태</th>
              <th>요청자</th>
            </tr>
          </thead>
          <tbody>
            {imports.data?.map((j) => (
              <tr key={j.id}>
                <td>{new Date(j.createdAt).toLocaleString("ko-KR")}</td>
                <td>
                  {String(j.params.layer)} · {String(j.params.source)}
                  {j.params.dryRun ? " (검증)" : ""}
                </td>
                <td>{j.status}</td>
                <td>{j.createdBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section aria-label="감사 로그">
        <h2 className="sub">감사 로그</h2>
        <label className="inline">
          작업
          <select value={auditAction} onChange={(e) => setAuditAction(e.target.value)}>
            <option value="">전체</option>
            {["DATA_IMPORT", "JOB_SUBMIT", "JOB_CANCEL", "FACILITY_PLAN", "FACILITY_PLAN_DELETE", "FACILITY_PLAN_CLEAR", "MODEL_UPLOAD", "MODEL_PLACE", "MODEL_DELETE"].map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
        </label>
        <table className="data-table audit">
          <thead>
            <tr>
              <th>시각</th>
              <th>사용자</th>
              <th>작업</th>
              <th>대상</th>
            </tr>
          </thead>
          <tbody>
            {audit.data?.map((a) => (
              <tr key={a.id} title={JSON.stringify(a.details)}>
                <td>{new Date(a.ts).toLocaleString("ko-KR")}</td>
                <td>{a.actorName ?? a.actorSub}</td>
                <td>{a.action}</td>
                <td>
                  {a.targetType ?? ""} {a.targetId ?? ""}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {audit.error && <p className="error-text">{(audit.error as Error).message}</p>}
      </section>
    </div>
  );
}

function ImportReport({ r }: { r: ImportResult }) {
  return (
    <section className="result" aria-label="적재 결과">
      <div className="tiles">
        <div className="tile">
          <div className="tile-label">유효 / 전체</div>
          <div className="tile-value">
            {r.valid} / {r.total}
          </div>
        </div>
        <div className="tile">
          <div className="tile-label">오류</div>
          <div className="tile-value">{r.invalid}</div>
        </div>
        <div className="tile">
          <div className="tile-label">{r.dryRun ? "보정 예정" : "신규 / 갱신"}</div>
          <div className="tile-value">{r.dryRun ? r.fixedGeometries : `${r.inserted} / ${r.updated}`}</div>
        </div>
        <div className="tile">
          <div className="tile-label">제거·비활성</div>
          <div className="tile-value">{r.removedOrDeactivated}</div>
        </div>
      </div>
      <p className="muted small">
        EPSG:{r.srid} · 범위 밖 {r.outOfBounds} · 지오메트리 보정 {r.fixedGeometries} · 파일 내 중복 {r.duplicates} · {r.elapsedMs}ms
        {r.dryRun && " · 검증만 했습니다(반영 안 됨)"}
      </p>
      {r.errors.length > 0 && (
        <details open>
          <summary>오류·경고 {r.errors.length}건</summary>
          <ul className="error-list">
            {r.errors.slice(0, 50).map((e, i) => (
              <li key={i}>{e}</li>
            ))}
          </ul>
        </details>
      )}
      {r.sample.length > 0 && (
        <details>
          <summary>변환 결과 샘플</summary>
          <pre className="code">{JSON.stringify(r.sample, null, 2)}</pre>
        </details>
      )}
    </section>
  );
}
