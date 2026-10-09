import { describe, expect, it, vi } from "vitest";
import { SseParser } from "./api/sse";
import { applyUiAction, type ActionTarget } from "./copilot/actions";
import { ageColor, AGE_RAMP, buildingColor, legendFor, riskColor, RISK_RAMP, USE_COLORS } from "./map/colors";
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
    expect(t.setHighlight).not.toHaveBeenCalled();
  });
});
