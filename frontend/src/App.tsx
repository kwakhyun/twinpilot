import { useSensorStream } from "./api/hooks";
import { CopilotChat } from "./copilot/CopilotChat";
import { mapHandle } from "./map/mapHandle";
import { TwinMap } from "./map/TwinMap";
import { AnalysisPanel } from "./panels/AnalysisPanel";
import { BuildingPanel } from "./panels/BuildingPanel";
import { DashboardPanel } from "./panels/DashboardPanel";
import { LayerPanel } from "./panels/LayerPanel";
import { ModelPanel } from "./panels/ModelPanel";
import { SensorStrip } from "./panels/SensorStrip";
import { useTwin } from "./state/store";

const TABS = [
  { key: "analysis", label: "분석" },
  { key: "dashboard", label: "대시보드" },
  { key: "layers", label: "레이어" },
  { key: "models", label: "3D 모델" },
] as const;

export default function App() {
  const activeTab = useTwin((s) => s.activeTab);
  const setActiveTab = useTwin((s) => s.setActiveTab);
  const { readings, connected } = useSensorStream();

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden>
            ◆
          </span>
          <strong>TwinPilot</strong>
          <span className="muted">디지털트윈 안전 코파일럿 · 소방·응급 커버리지 팩</span>
        </div>
        <SensorStrip readings={readings} connected={connected} />
      </header>
      <div className="main">
        <nav className="sidebar" aria-label="작업 패널">
          <div className="tabs" role="tablist">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={activeTab === t.key}
                className={activeTab === t.key ? "active" : ""}
                onClick={() => setActiveTab(t.key)}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="tab-body" role="tabpanel">
            {activeTab === "analysis" && <AnalysisPanel />}
            {activeTab === "dashboard" && <DashboardPanel />}
            {activeTab === "layers" && <LayerPanel />}
            {activeTab === "models" && <ModelPanel />}
          </div>
        </nav>
        <main className="stage">
          <TwinMap sensors={readings} onReady={mapHandle.set} />
          <BuildingPanel />
          <CopilotChat />
        </main>
      </div>
    </div>
  );
}
