import { describe, expect, it, vi } from "vitest";
import { SseParser, subscribeSse } from "./api/sse";
import { hasRole } from "./auth/session";
import { applyUiAction, type ActionTarget } from "./copilot/actions";
import { ageColor, AGE_RAMP, buildingColor, isochroneColor, legendFor, riskColor, RISK_RAMP, USE_COLORS } from "./map/colors";
import { centerOf, pointInGeometry, pointInPolygon } from "./map/geo";

describe("SseParser", () => {
  it("이벤트가 청크 경계에서 잘려도 완성된 것만 돌려준다", () => {
    const p = new SseParser();
    expect(p.push("event: tool_call\ndata: {\"a\":")).toEqual([]);
    expect(p.push("1}\n\nevent: token\r\ndata: 안녕\r\n\r\n: keep-alive\n\n")).toEqual([
      { event: "tool_call", data: '{"a":1}' },
      { event: "token", data: "안녕" },
    ]);
  });

  it("여러 data 줄은 개행으로 합친다", () => {
    expect(new SseParser().push("data: a\ndata: b\n\n")).toEqual([{ event: "message", data: "a\nb" }]);
  });
});

describe("colors", () => {
  it("위험도 구간 경계", () => {
    expect(riskColor(0)).toBe(RISK_RAMP[0]);
    expect(riskColor(19.9)).toBe(RISK_RAMP[0]);
    expect(riskColor(20)).toBe(RISK_RAMP[1]);
    expect(riskColor(85)).toBe(RISK_RAMP[4]);
    expect(riskColor(null)).toBe(USE_COLORS.other);
  });

  it("노후도는 기준연도 대비 경과연수", () => {
    expect(ageColor(2020, 2026)).toBe(AGE_RAMP[0]);
    expect(ageColor(1980, 2026)).toBe(AGE_RAMP[4]);
  });

  it("용도는 3개 그룹 + 기타로 접는다", () => {
    expect(buildingColor("use", { risk: 1, builtYear: 2000, useType: "RESIDENTIAL_APT" })).toBe(USE_COLORS.residential);
    expect(buildingColor("use", { risk: 1, builtYear: 2000, useType: "WAREHOUSE" })).toBe(USE_COLORS.industrial);
    expect(buildingColor("use", { risk: 1, builtYear: 2000, useType: "SCHOOL" })).toBe(USE_COLORS.other);
    expect(legendFor("use")).toHaveLength(4);
  });
});

describe("geo", () => {
  const square: [number, number][] = [
    [0, 0],
    [10, 0],
    [10, 10],
    [0, 10],
    [0, 0],
  ];
  const hole: [number, number][] = [
    [4, 4],
    [6, 4],
    [6, 6],
    [4, 6],
    [4, 4],
  ];

  it("point in polygon(구멍 제외)", () => {
    expect(pointInPolygon(5, 1, [square])).toBe(true);
    expect(pointInPolygon(11, 1, [square])).toBe(false);
    expect(pointInPolygon(5, 5, [square, hole])).toBe(false);
    expect(pointInGeometry(5, 1, { type: "MultiPolygon", coordinates: [[square]] })).toBe(true);
  });

  it("centerOf는 최소 반경 80m를 보장한다", () => {
    const c = centerOf([{ lon: 129, lat: 35 }])!;
    expect(c.lon).toBe(129);
    expect(c.radiusM).toBe(80);
    expect(centerOf([])).toBeNull();
  });
});

describe("applyUiAction", () => {
  const target = (): ActionTarget => ({
    setHighlight: vi.fn(),
    flyTo: vi.fn(),
    selectBuilding: vi.fn(),
    presetAnalysis: vi.fn(),
    setRecommendation: vi.fn(),
    setColorMode: vi.fn(),
    setLayer: vi.fn(),
    showIsochrones: vi.fn(),
  });

  it("정상 액션을 적용한다", () => {
    const t = target();
    expect(applyUiAction({ type: "highlightBuildings", ids: [1, 2], label: "x" }, t)).toBe(true);
    expect(t.setHighlight).toHaveBeenCalledWith({ ids: [1, 2], label: "x" });
    expect(applyUiAction({ type: "runCoverage", facilityType: "AED", regionId: 4 }, t)).toBe(true);
    expect(t.presetAnalysis).toHaveBeenCalledWith({ facilityType: "AED", regionId: 4, radiusM: null });
  });

  it("형식이 틀리거나 모르는 액션은 무시한다", () => {
    const t = target();
    expect(applyUiAction({ type: "highlightBuildings", ids: ["1; DROP"] }, t)).toBe(false);
    expect(applyUiAction({ type: "runCoverage", facilityType: "BOMB" }, t)).toBe(false);
    expect(applyUiAction({ type: "showLayer", layer: "__proto__", visible: true }, t)).toBe(false);
    expect(applyUiAction({ type: "eval", code: "alert(1)" }, t)).toBe(false);
    expect(applyUiAction(null, t)).toBe(false);
    expect(applyUiAction({ type: "showIsochrones", minutes: [3, 999] }, t)).toBe(false);
    expect(applyUiAction({ type: "showIsochrones", minutes: [1, 2, 3, 4, 5, 6] }, t)).toBe(false);
    expect(t.setHighlight).not.toHaveBeenCalled();
    expect(t.showIsochrones).not.toHaveBeenCalled();
  });

  it("출동 도달권은 중복을 없애고 오름차순으로 표시한다", () => {
    const t = target();
    expect(applyUiAction({ type: "showIsochrones", minutes: [7, 3, 5, 7] }, t)).toBe(true);
    expect(t.showIsochrones).toHaveBeenCalledWith([3, 5, 7]);
  });
});

describe("역할 계층", () => {
  it("상위 역할은 하위 권한을 포함한다", () => {
    expect(hasRole(["PLANNER"], "ANALYST")).toBe(true);
    expect(hasRole(["PLANNER"], "VIEWER")).toBe(true);
    expect(hasRole(["ANALYST"], "PLANNER")).toBe(false);
    expect(hasRole(["INGEST"], "VIEWER")).toBe(false);
    expect(hasRole([], "VIEWER")).toBe(false);
  });
});

describe("출동 도달권 색상", () => {
  it("짧은 시간일수록 진한 색(순차 팔레트 양끝)을 쓴다", () => {
    expect(isochroneColor(3, [3, 5, 7])).toBe("#184f95");
    expect(isochroneColor(7, [3, 5, 7])).toBe("#b7d3f6");
    expect(isochroneColor(5, [5])).toBe("#184f95");
  });
});

describe("subscribeSse", () => {
  it("끊기면 다시 연결하고, 중지 조건이면 멈춘다", async () => {
    vi.useFakeTimers();
    const bodies = ["event: progress\ndata: {\"p\":1}\n\n", "event: done\ndata: {}\n\n"];
    const fetchMock = vi.fn(async () => {
      const text = bodies.shift() ?? "";
      return new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode(text)); c.close(); } }),
        { status: 200, headers: { "Content-Type": "text/event-stream" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    const events: string[] = [];
    let done = false;
    const stop = subscribeSse("/x", { onEvent: (e) => { events.push(e.event); if (e.event === "done") done = true; },
                                       shouldStop: () => done });
    await vi.advanceTimersByTimeAsync(5000);
    expect(events).toEqual(["progress", "done"]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    stop();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });
});
