import { create } from "zustand";
import type { ColorMode, CoverageResult, FacilityType, IsochroneCollection, RecommendationResult } from "../api/types";

export type LayerKey =
  | "buildings"
  | "regions"
  | "roads"
  | "hydrants"
  | "aeds"
  | "stations"
  | "sensors"
  | "busRoutes"
  | "models"
  | "isochrones"
  | "osm";

export type TabKey = "layers" | "analysis" | "dashboard" | "models" | "admin";

export interface Toast {
  id: number;
  kind: "info" | "success" | "error";
  text: string;
}

export interface Highlight {
  ids: number[];
  label: string;
}

export interface CameraTarget {
  lon: number;
  lat: number;
  height?: number;
  key: number;
}

interface TwinState {
  layers: Record<LayerKey, boolean>;
  colorMode: ColorMode;
  selectedBuildingId: number | null;
  highlight: Highlight | null;
  coverage: CoverageResult | null;
  recommendation: RecommendationResult | null;
  camera: CameraTarget | null;
  isochrones: { minutes: number[]; data: IsochroneCollection | null } | null;
  toasts: Toast[];
  activeTab: TabKey;
  analysisPreset: { facilityType: FacilityType; regionId: number | null; radiusM: number | null; key: number } | null;
  copilotPrompt: { text: string; key: number } | null;

  toggleLayer: (k: LayerKey) => void;
  setLayer: (k: LayerKey, v: boolean) => void;
  setColorMode: (m: ColorMode) => void;
  selectBuilding: (id: number | null) => void;
  setHighlight: (h: Highlight | null) => void;
  setCoverage: (c: CoverageResult | null) => void;
  setRecommendation: (r: RecommendationResult | null) => void;
  flyTo: (lon: number, lat: number, height?: number) => void;
  setActiveTab: (t: TabKey) => void;
  showIsochrones: (minutes: number[] | null) => void;
  setIsochroneData: (data: IsochroneCollection | null) => void;
  toast: (kind: Toast["kind"], text: string) => void;
  dismissToast: (id: number) => void;
  presetAnalysis: (p: { facilityType: FacilityType; regionId: number | null; radiusM: number | null }) => void;
  askCopilot: (text: string) => void;
}

export const useTwin = create<TwinState>((set) => ({
  layers: {
    buildings: true,
    regions: true,
    roads: true,
    hydrants: true,
    aeds: false,
    stations: true,
    sensors: true,
    busRoutes: false,
    models: true,
    isochrones: true,
    osm: false,
  },
  colorMode: "risk",
  selectedBuildingId: null,
  highlight: null,
  coverage: null,
  recommendation: null,
  camera: null,
  isochrones: null,
  toasts: [],
  activeTab: "analysis",
  analysisPreset: null,
  copilotPrompt: null,

  toggleLayer: (k) => set((s) => ({ layers: { ...s.layers, [k]: !s.layers[k] } })),
  setLayer: (k, v) => set((s) => ({ layers: { ...s.layers, [k]: v } })),
  setColorMode: (colorMode) => set({ colorMode }),
  selectBuilding: (selectedBuildingId) => set({ selectedBuildingId }),
  setHighlight: (highlight) => set({ highlight }),
  setCoverage: (coverage) => set({ coverage }),
  setRecommendation: (recommendation) => set({ recommendation }),
  flyTo: (lon, lat, height) => set({ camera: { lon, lat, height, key: Date.now() + Math.random() } }),
  setActiveTab: (activeTab) => set({ activeTab }),
  showIsochrones: (minutes) =>
    set((s) => ({
      isochrones: minutes ? { minutes, data: null } : null,
      layers: minutes ? { ...s.layers, isochrones: true } : s.layers,
    })),
  setIsochroneData: (data) => set((s) => (s.isochrones ? { isochrones: { ...s.isochrones, data } } : {})),
  toast: (kind, text) => {
    const id = Date.now() + Math.random();
    set((s) => ({ toasts: [...s.toasts.slice(-3), { id, kind, text }] }));
    setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), kind === "error" ? 8000 : 4000);
  },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  presetAnalysis: (p) => set({ analysisPreset: { ...p, key: Date.now() }, activeTab: "analysis" }),
  askCopilot: (text) => set({ copilotPrompt: { text, key: Date.now() } }),
}));
