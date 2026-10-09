import { create } from "zustand";
import type { ColorMode, CoverageResult, FacilityType, RecommendationResult } from "../api/types";

export type LayerKey = "buildings" | "regions" | "roads" | "hydrants" | "aeds" | "stations" | "sensors" | "busRoutes" | "models" | "osm";

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
  activeTab: "layers" | "analysis" | "dashboard" | "models";
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
  setActiveTab: (t: TwinState["activeTab"]) => void;
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
    osm: false,
  },
  colorMode: "risk",
  selectedBuildingId: null,
  highlight: null,
  coverage: null,
  recommendation: null,
  camera: null,
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
  presetAnalysis: (p) => set({ analysisPreset: { ...p, key: Date.now() }, activeTab: "analysis" }),
  askCopilot: (text) => set({ copilotPrompt: { text, key: Date.now() } }),
}));
