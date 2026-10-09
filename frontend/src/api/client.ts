import type {
  AuditEntry,
  BuildingDetail,
  BuildingProps,
  CoverageRequest,
  CoverageResult,
  FacilityProps,
  FacilityType,
  FeatureCollection,
  GeoJSONGeometry,
  ImportResult,
  IsochroneCollection,
  JobView,
  Me,
  ModelAsset,
  RecommendationResult,
  RegionProps,
  ResponseResult,
  SensorReading,
  StatsSummary,
} from "./types";
import { authFetch, errorMessage } from "./http";
import { subscribeSse } from "./sse";

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
  const res = await authFetch(API + path, init);
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res));
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

const json = (body: unknown, method = "POST"): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export interface JobRequest {
  type: "RECOMMEND_SITES" | "REFRESH_RISK";
  facilityType?: FacilityType;
  count?: number;
  radiusM?: number | null;
  regionId?: number | null;
}

export const api = {
  me: () => request<Me>("/me"),
  regions: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, RegionProps>>("/regions", { signal }),
  roads: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, { name: string; widthM: number }>>("/roads", { signal }),
  busRoutes: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, { name: string }>>("/bus-routes", { signal }),
  buildings: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, BuildingProps>>("/buildings", { signal }),
  facilities: (signal?: AbortSignal) => request<FeatureCollection<GeoJSONGeometry, FacilityProps>>("/facilities", { signal }),
  building: (id: number, signal?: AbortSignal) => request<BuildingDetail>(`/buildings/${id}`, { signal }),
  coverage: (req: CoverageRequest, signal?: AbortSignal) =>
    request<CoverageResult>("/analysis/coverage", { ...json(req), signal }),
  response: (body: { regionId?: number | null; targetMin?: number; top?: number }, signal?: AbortSignal) =>
    request<ResponseResult>("/analysis/response", { ...json(body), signal }),
  isochrones: (minutes: number[], signal?: AbortSignal) =>
    request<IsochroneCollection>(`/analysis/isochrones?minutes=${minutes.join(",")}`, { signal }),
  createJob: <R = RecommendationResult>(body: JobRequest, idempotencyKey?: string) =>
    request<JobView<R>>("/analysis/jobs", {
      ...json(body),
      headers: { "Content-Type": "application/json", ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}) },
    }),
  job: <R = RecommendationResult>(id: string) => request<JobView<R>>(`/analysis/jobs/${id}`),
  jobs: (params: { all?: boolean; type?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.all) q.set("all", "true");
    if (params.type) q.set("type", params.type);
    q.set("limit", String(params.limit ?? 20));
    return request<JobView[]>(`/analysis/jobs?${q}`);
  },
  cancelJob: (id: string) => request<JobView>(`/analysis/jobs/${id}`, { method: "DELETE" }),
  stats: (signal?: AbortSignal) => request<StatsSummary>("/stats/summary", { signal }),
  sensors: () => request<{ total: number; alerts: number; lastReading: string | null; readings: SensorReading[] }>("/sensors"),
  addPlanned: (body: { type: FacilityType; lon: number; lat: number; name?: string }) =>
    request<{ id: number }>("/facilities", json(body)),
  clearPlanned: () => request<{ deleted: number }>("/facilities/planned", { method: "DELETE" }),
  models: () => request<ModelAsset[]>("/models"),
  uploadModel: (form: FormData) => request<ModelAsset>("/models", { method: "POST", body: form }),
  placeModel: (id: string, body: { lon: number; lat: number; heightM: number; headingDeg: number; scale: number }) =>
    request<ModelAsset>(`/models/${id}/placement`, json(body, "PUT")),
  deleteModel: (id: string) => request<void>(`/models/${id}`, { method: "DELETE" }),
  /** 모델 파일은 인증이 필요하므로 Blob URL로 받아 Cesium·Three.js에 넘긴다. 다 쓰면 revoke한다. */
  modelBlobUrl: async (id: string) => {
    const res = await authFetch(`${API}/models/${id}/file`);
    if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
    return URL.createObjectURL(await res.blob());
  },
  importData: (form: FormData) => request<JobView<ImportResult>>("/admin/imports", { method: "POST", body: form }),
  imports: () => request<JobView<ImportResult>[]>("/admin/imports"),
  audit: (params: { action?: string; actor?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.action) q.set("action", params.action);
    if (params.actor) q.set("actor", params.actor);
    q.set("limit", String(params.limit ?? 50));
    return request<AuditEntry[]>(`/admin/audit?${q}`);
  },
};

/** Job 진행률 SSE. 끊기면 다시 연결하고(서버가 현재 상태부터 다시 보낸다), 최종 이벤트를 받으면 끝낸다. */
export function watchJob<R>(
  id: string,
  handlers: {
    progress: (pct: number, message: string) => void;
    done: (job: JobView<R>) => void;
    failed: (job: JobView<R>) => void;
  },
): () => void {
  let finished = false;
  const stop = subscribeSse(`${API}/analysis/jobs/${id}/events`, {
    shouldStop: () => finished,
    onEvent: (e) => {
      const d = JSON.parse(e.data);
      if (e.event === "progress") handlers.progress(d.progress, d.message);
      else if (e.event === "done") {
        finished = true;
        handlers.done(d);
        stop();
      } else if (e.event === "failed" || e.event === "cancelled") {
        finished = true;
        handlers.failed(d);
        stop();
      }
    },
  });
  return () => {
    finished = true;
    stop();
  };
}
