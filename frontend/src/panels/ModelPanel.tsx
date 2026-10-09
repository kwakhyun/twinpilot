import { lazy, Suspense, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { useModels } from "../api/hooks";
import type { ModelAsset } from "../api/types";
import { useCan } from "../auth/session";
import { mapHandle } from "../map/mapHandle";
import { useTwin } from "../state/store";

const ModelViewer = lazy(() => import("../three/ModelViewer"));

const fmt = (v: number) => v.toLocaleString("ko-KR");

export function ModelPanel() {
  const qc = useQueryClient();
  const models = useModels();
  const flyTo = useTwin((s) => s.flyTo);
  const toast = useTwin((s) => s.toast);
  const canEdit = useCan("PLANNER");
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<string | null>(null);

  async function upload(f: File, displayName: string) {
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", f);
      if (displayName) form.append("name", displayName);
      const center = mapHandle.get()?.screenCenter();
      if (center) {
        form.append("lon", String(center.lon));
        form.append("lat", String(center.lat));
      }
      await api.uploadModel(form);
      toast("info", "업로드했습니다. 서버에서 검증 중입니다.");
      await qc.invalidateQueries({ queryKey: ["models"] });
      setFile(null);
      setName("");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function uploadSample() {
    const res = await fetch("/samples/landmark-tower.glb");
    const blob = await res.blob();
    await upload(new File([blob], "landmark-tower.glb", { type: "model/gltf-binary" }), "샘플 랜드마크 타워");
  }

  async function update(m: ModelAsset, patch: Partial<Pick<ModelAsset, "lon" | "lat" | "headingDeg">>) {
    try {
      await api.placeModel(m.id, {
        lon: patch.lon ?? m.lon ?? 0,
        lat: patch.lat ?? m.lat ?? 0,
        heightM: m.heightM,
        headingDeg: patch.headingDeg ?? m.headingDeg,
        scale: m.scale,
      });
      await qc.invalidateQueries({ queryKey: ["models"] });
    } catch (e) {
      toast("error", (e as Error).message);
    }
  }

  async function remove(m: ModelAsset) {
    if (!window.confirm(`'${m.name}' 모델을 삭제할까요?`)) return;
    try {
      await api.deleteModel(m.id);
      if (preview === m.id) setPreview(null);
      await qc.invalidateQueries({ queryKey: ["models"] });
    } catch (e) {
      toast("error", (e as Error).message);
    }
  }

  return (
    <div className="models">
      {!canEdit && <p className="muted small">모델 업로드·배치는 계획 담당(PLANNER) 이상만 할 수 있습니다.</p>}
      {canEdit && (
      <form
        className="form-grid"
        onSubmit={(e) => {
          e.preventDefault();
          if (file) void upload(file, name);
        }}
      >
        <label className="span2">
          glTF/GLB 파일
          <input type="file" accept=".glb,.gltf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </label>
        <label className="span2">
          이름
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="예: 신축 청사" maxLength={128} />
        </label>
        <div className="btn-row span2">
          <button type="submit" className="btn primary" disabled={!file || busy}>
            업로드(화면 중심에 배치)
          </button>
          <button type="button" className="btn" onClick={() => void uploadSample()} disabled={busy}>
            샘플 모델
          </button>
        </div>
      </form>
      )}
      {error && <p className="error-text" role="alert">{error}</p>}
      <p className="muted small">업로드 후 서버에서 비동기로 glTF 구조를 검증하고 정점·삼각형 수, 크기를 추출합니다.</p>

      <ul className="model-list">
        {models.data?.map((m) => (
          <li key={m.id} className="model-item">
            <div className="model-head">
              <strong>{m.name}</strong>
              <span className={`chip ${m.status === "FAILED" ? "critical" : m.status === "PROCESSING" ? "warn" : ""}`}>{m.status}</span>
            </div>
            {m.status === "FAILED" && <p className="error-text small">{m.error}</p>}
            {m.createdBy && <p className="muted small">등록: {m.createdBy}</p>}
            {m.stats && (
              <p className="muted small">
                메시 {m.stats.meshes} · 정점 {fmt(m.stats.vertices)} · 삼각형 {fmt(m.stats.triangles)}
                {m.stats.sizeM && ` · ${m.stats.sizeM.map((v) => v.toFixed(0)).join("×")}m`} · {(m.sizeBytes / 1024).toFixed(1)}KB ·{" "}
                {m.stats.storage}
                {m.stats.warnings.map((w) => (
                  <span key={w} className="warn-line">
                    ⚠ {w}
                  </span>
                ))}
              </p>
            )}
            {m.status === "READY" && (
              <div className="btn-row">
                <button type="button" className="btn small" onClick={() => setPreview(preview === m.id ? null : m.id)}>
                  {preview === m.id ? "미리보기 닫기" : "Three.js 미리보기"}
                </button>
                {m.lon != null && m.lat != null && (
                  <button type="button" className="btn small" onClick={() => flyTo(m.lon!, m.lat!, 600)}>
                    위치로 이동
                  </button>
                )}
                {canEdit && (
                  <>
                    <button
                      type="button"
                      className="btn small"
                      onClick={() => {
                        const c = mapHandle.get()?.screenCenter();
                        if (c) void update(m, c);
                      }}
                    >
                      화면 중심으로
                    </button>
                    <button type="button" className="btn small" onClick={() => void update(m, { headingDeg: (m.headingDeg + 15) % 360 })}>
                      회전 +15°
                    </button>
                    <button type="button" className="btn small danger" onClick={() => void remove(m)}>
                      삭제
                    </button>
                  </>
                )}
              </div>
            )}
            {preview === m.id && (
              <Suspense fallback={<div className="three-view loading">3D 엔진 불러오는 중…</div>}>
                <ModelViewer modelId={m.id} />
              </Suspense>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
