// 지도 색상 스케일. 모든 램프는 dataviz 검증기(validate_palette.js)로 어두운 지도 표면(#111214) 기준 통과값이다.
//  - 위험도: 단일 색상(주황) 순차 5단계, 위험할수록 밝다
//  - 노후도: 단일 색상(파랑) 순차 5단계, 오래될수록 밝다
//  - 용도: 범주 3색(전 쌍 검증 통과) + 중립 회색 '기타'
import type { ColorMode } from "../api/types";

export interface LegendItem {
  label: string;
  color: string;
}

export const RISK_RAMP = ["#9c470d", "#c55f1d", "#e87835", "#ff9a64", "#ffc19b"];
export const AGE_RAMP = ["#184f95", "#256abf", "#3987e5", "#6da7ec", "#b7d3f6"];
export const USE_COLORS = { residential: "#3987e5", commercial: "#d95926", industrial: "#199e70", other: "#8a8a84" };
export const HIGHLIGHT = "#f2f2ee";
export const DIMMED = "#2a2c30";

export const STATUS = { good: "#0ca30c", warning: "#fab219", serious: "#ec835a", critical: "#d03b3b" };

const RISK_BREAKS = [20, 40, 60, 80];
const AGE_BREAKS = [10, 20, 30, 40];

function classify(value: number, breaks: number[]): number {
  let i = 0;
  while (i < breaks.length && value >= breaks[i]) i++;
  return i;
}

export function riskColor(risk: number | null | undefined): string {
  if (risk == null) return USE_COLORS.other;
  return RISK_RAMP[classify(risk, RISK_BREAKS)];
}

export function ageColor(builtYear: number | null | undefined, now = new Date().getFullYear()): string {
  if (builtYear == null) return USE_COLORS.other;
  return AGE_RAMP[classify(now - builtYear, AGE_BREAKS)];
}

export function useGroup(useType: string): keyof typeof USE_COLORS {
  if (useType.startsWith("RESIDENTIAL")) return "residential";
  if (useType === "COMMERCIAL" || useType === "OFFICE") return "commercial";
  if (useType === "INDUSTRIAL" || useType === "WAREHOUSE") return "industrial";
  return "other";
}

export function buildingColor(
  mode: ColorMode,
  p: { risk: number | null; builtYear: number | null; useType: string },
): string {
  switch (mode) {
    case "risk":
      return riskColor(p.risk);
    case "age":
      return ageColor(p.builtYear);
    case "use":
      return USE_COLORS[useGroup(p.useType)];
  }
}

export function legendFor(mode: ColorMode): LegendItem[] {
  switch (mode) {
    case "risk":
      return ["0–20", "20–40", "40–60", "60–80", "80–100"].map((label, i) => ({ label, color: RISK_RAMP[i] }));
    case "age":
      return ["10년 미만", "10–20년", "20–30년", "30–40년", "40년 이상"].map((label, i) => ({ label, color: AGE_RAMP[i] }));
    case "use":
      return [
        { label: "주거", color: USE_COLORS.residential },
        { label: "상업·업무", color: USE_COLORS.commercial },
        { label: "산업·물류", color: USE_COLORS.industrial },
        { label: "공공·교육·기타", color: USE_COLORS.other },
      ];
  }
}

export const USE_LABELS: Record<string, string> = {
  RESIDENTIAL_APT: "공동주택",
  RESIDENTIAL_HOUSE: "단독·다가구",
  COMMERCIAL: "근린상업",
  OFFICE: "업무",
  INDUSTRIAL: "공장",
  WAREHOUSE: "창고",
  PUBLIC: "공공",
  SCHOOL: "학교",
};

export const STRUCTURE_LABELS: Record<string, string> = {
  RC: "철근콘크리트",
  STEEL: "철골",
  MASONRY: "조적",
  WOOD: "목조",
};

export const FACILITY_LABELS: Record<string, string> = {
  HYDRANT: "소화전",
  AED: "AED",
  FIRE_STATION: "소방서",
};
