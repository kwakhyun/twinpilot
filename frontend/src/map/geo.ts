import type { GeoJSONGeometry, Position } from "../api/types";

/** Polygon/MultiPolygon을 폴리곤(링 배열) 목록으로 펼친다. */
export function polygonsOf(geometry: GeoJSONGeometry): Position[][][] {
  if (geometry.type === "Polygon") return [geometry.coordinates];
  if (geometry.type === "MultiPolygon") return geometry.coordinates;
  return [];
}

/** 레이캐스팅 point-in-polygon (외곽 링 기준, 구멍 링은 제외 처리). */
export function pointInPolygon(lon: number, lat: number, rings: Position[][]): boolean {
  const inside = (ring: Position[]) => {
    let c = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const [xi, yi] = ring[i];
      const [xj, yj] = ring[j];
      if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) c = !c;
    }
    return c;
  };
  if (rings.length === 0 || !inside(rings[0])) return false;
  return !rings.slice(1).some(inside);
}

export function pointInGeometry(lon: number, lat: number, geometry: GeoJSONGeometry): boolean {
  return polygonsOf(geometry).some((rings) => pointInPolygon(lon, lat, rings));
}

/** 좌표 목록의 중심(단순 평균)과 대략 반경(m). 카메라 이동용. */
export function centerOf(points: { lon: number; lat: number }[]): { lon: number; lat: number; radiusM: number } | null {
  if (points.length === 0) return null;
  const lon = points.reduce((s, p) => s + p.lon, 0) / points.length;
  const lat = points.reduce((s, p) => s + p.lat, 0) / points.length;
  const mPerLon = 111_320 * Math.cos((lat * Math.PI) / 180);
  const radiusM = Math.max(
    80,
    ...points.map((p) => Math.hypot((p.lon - lon) * mPerLon, (p.lat - lat) * 110_540)),
  );
  return { lon, lat, radiusM };
}

/** 폴리곤 외곽 링의 꼭짓점 평균(작은 건물 대표점으로 충분). */
export function ringCentroid(geometry: GeoJSONGeometry): { lon: number; lat: number } | null {
  const polys = polygonsOf(geometry);
  if (polys.length === 0) return null;
  const ring = polys[0][0].slice(0, -1);
  return {
    lon: ring.reduce((s, p) => s + p[0], 0) / ring.length,
    lat: ring.reduce((s, p) => s + p[1], 0) / ring.length,
  };
}
