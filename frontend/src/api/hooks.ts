import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { SensorReading } from "./types";

// 레이어 데이터는 자주 바뀌지 않으므로 캐시를 길게 잡고(ETag로 서버 재전송도 줄어든다) 중복 요청을 합친다.
const LONG = 5 * 60_000;

export const useRegions = () => useQuery({ queryKey: ["regions"], queryFn: ({ signal }) => api.regions(signal), staleTime: LONG });
export const useRoads = () => useQuery({ queryKey: ["roads"], queryFn: ({ signal }) => api.roads(signal), staleTime: LONG });
export const useBusRoutes = () => useQuery({ queryKey: ["busRoutes"], queryFn: ({ signal }) => api.busRoutes(signal), staleTime: LONG });
export const useBuildings = () => useQuery({ queryKey: ["buildings"], queryFn: ({ signal }) => api.buildings(signal), staleTime: LONG });
export const useFacilities = () => useQuery({ queryKey: ["facilities"], queryFn: ({ signal }) => api.facilities(signal), staleTime: LONG });
export const useStats = () => useQuery({ queryKey: ["stats"], queryFn: ({ signal }) => api.stats(signal), staleTime: 30_000 });
export const useModels = () =>
  useQuery({
    queryKey: ["models"],
    queryFn: api.models,
    // 처리 중인 모델이 있으면 상태가 바뀔 때까지 짧게 폴링한다.
    refetchInterval: (q) => (q.state.data?.some((m) => m.status === "PROCESSING") ? 1000 : false),
  });

export const useBuilding = (id: number | null) =>
  useQuery({
    queryKey: ["building", id],
    queryFn: ({ signal }) => api.building(id!, signal),
    enabled: id != null,
    staleTime: 10_000,
  });

/** 센서 SSE 구독. 연결이 끊기면 EventSource가 자동 재연결한다. */
export function useSensorStream(): { readings: SensorReading[]; connected: boolean } {
  const [readings, setReadings] = useState<SensorReading[]>([]);
  const [connected, setConnected] = useState(false);
  useEffect(() => {
    const es = new EventSource("/api/v1/sensors/stream");
    es.addEventListener("readings", (e) => {
      setReadings(JSON.parse((e as MessageEvent).data));
      setConnected(true);
    });
    es.onerror = () => setConnected(false);
    return () => es.close();
  }, []);
  return { readings, connected };
}
