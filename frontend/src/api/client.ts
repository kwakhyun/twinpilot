import type {
  BuildingDetail,
  BuildingProps,
  CoverageRequest,
  CoverageResult,
  FacilityProps,
  FacilityType,
  FeatureCollection,
  GeoJSONGeometry,
  JobView,
  ModelAsset,
  RecommendationResult,
  RegionProps,
  SensorReading,
  StatsSummary,
} from "./types";

const API = "/api/v1";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(API + path, init);
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = body.detail ?? body.error ?? detail;
    } catch {
      // 본문이 JSON이 아닌 오류
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

const json = (body: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const api = {
  regions: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, RegionProps>>("/regions", { signal }),
  roads: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, { name: string; widthM: number }>>("/roads", { signal }),
  busRoutes: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, { name: string }>>("/bus-routes", { signal }),
  buildings: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, BuildingProps>>("/buildings", { signal }),
  facilities: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, FacilityProps>>("/facilities", { signal }),
  building: (id: number, signal?: AbortSignal) => request<BuildingDetail>(`/buildings/${id}`, { signal }),
  coverage: (req: CoverageRequest, signal?: AbortSignal) =>
    request<CoverageResult>("/analysis/coverage", { ...json(req), signal }),
  createJob: (body: { type: string; facilityType?: FacilityType; count?: number; radiusM?: number | null; regionId?: number | null }) =>
    request<JobView<RecommendationResult>>("/analysis/jobs", json(body)),
  job: (id: string) => request<JobView<RecommendationResult>>(`/analysis/jobs/${id}`),
  stats: (signal?: AbortSignal) => request<StatsSummary>("/stats/summary", { signal }),
  sensors: () => request<{ total: number; alerts: number; readings: SensorReading[] }>("/sensors"),
  addPlanned: (body: { type: FacilityType; lon: number; lat: number; name?: string }) =>
    request<{ id: number }>("/facilities", json(body)),
  clearPlanned: () => request<{ deleted: number }>("/facilities/planned", { method: "DELETE" }),
  models: () => request<ModelAsset[]>("/models"),
  uploadModel: (form: FormData) => request<ModelAsset>("/models", { method: "POST", body: form }),
  placeModel: (id: string, body: { lon: number; lat: number; heightM: number; headingDeg: number; scale: number }) =>
    request<ModelAsset>(`/models/${id}/placement`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
  deleteModel: (id: string) => request<void>(`/models/${id}`, { method: "DELETE" }),
  modelFileUrl: (id: string) => `${API}/models/${id}/file`,
};

/** Job 진행률 SSE(EventSource). 완료/실패 시 자동으로 닫는다. */
export function watchJob<R>(
  id: string,
  handlers: { progress: (pct: number, message: string) => void; done: (job: JobView<R>) => void; failed: (job: JobView<R>) => void },
): () => void {
  const es = new EventSource(`${API}/analysis/jobs/${id}/events`);
  es.addEventListener("progress", (e) => {
    const d = JSON.parse((e as MessageEvent).data);
    handlers.progress(d.progress, d.message);
  });
  es.addEventListener("done", (e) => {
    handlers.done(JSON.parse((e as MessageEvent).data));
    es.close();
  });
  es.addEventListener("failed", (e) => {
    handlers.failed(JSON.parse((e as MessageEvent).data));
    es.close();
  });
  es.onerror = () => {
    // 연결이 끊기면 폴링으로 최종 상태를 확인한다.
    es.close();
    void api.job(id).then((job) => {
      if (job.status === "SUCCEEDED") handlers.done(job as JobView<R>);
      else if (job.status === "FAILED") handlers.failed(job as JobView<R>);
    });
  };
  return () => es.close();
}
