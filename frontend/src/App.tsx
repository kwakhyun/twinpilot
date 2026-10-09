import { useSensorStream } from "./api/hooks";
import { logout, useCan, useSession } from "./auth/session";
import { CopilotChat } from "./copilot/CopilotChat";
import { mapHandle } from "./map/mapHandle";
import { TwinMap } from "./map/TwinMap";
import { AdminPanel } from "./panels/AdminPanel";
import { AnalysisPanel } from "./panels/AnalysisPanel";
import { BuildingPanel } from "./panels/BuildingPanel";
import { DashboardPanel } from "./panels/DashboardPanel";
import { LayerPanel } from "./panels/LayerPanel";
import { ModelPanel } from "./panels/ModelPanel";
import { SensorStrip } from "./panels/SensorStrip";
import { useTwin, type TabKey } from "./state/store";
import { ErrorBoundary } from "./ui/ErrorBoundary";
import { Toasts } from "./ui/Toasts";

const ROLE_LABEL: Record<string, string> = { ADMIN: "관리자", PLANNER: "계획", ANALYST: "분석", VIEWER: "조회" };

export default function App() {
  const activeTab = useTwin((s) => s.activeTab);
  const setActiveTab = useTwin((s) => s.setActiveTab);
  const isAdmin = useCan("ADMIN");
  const { readings, connected } = useSensorStream();
  const tabs: { key: TabKey; label: string }[] = [
    { key: "analysis", label: "분석" },
    { key: "dashboard", label: "대시보드" },
    { key: "layers", label: "레이어" },
    { key: "models", label: "3D 모델" },
    ...(isAdmin ? [{ key: "admin" as const, label: "관리" }] : []),
  ];

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo" aria-hidden>
            ◆
          </span>
          <h1 className="brand-title">TwinPilot</h1>
          <span className="muted">디지털트윈 안전 코파일럿 · 소방·응급 커버리지 팩</span>
        </div>
        <SensorStrip readings={readings} connected={connected} />
        <UserMenu />
      </header>
      <div className="main">
        <nav className="sidebar" aria-label="작업 패널">
          <div className="tabs" role="tablist">
            {tabs.map((t) => (
              <button
                key={t.key}
                id={`tab-${t.key}`}
                type="button"
                role="tab"
                aria-selected={activeTab === t.key}
                aria-controls="tab-panel"
                className={activeTab === t.key ? "active" : ""}
                onClick={() => setActiveTab(t.key)}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="tab-body" role="tabpanel" id="tab-panel" aria-labelledby={`tab-${activeTab}`}>
            <ErrorBoundary name="패널" key={activeTab}>
              {activeTab === "analysis" && <AnalysisPanel />}
              {activeTab === "dashboard" && <DashboardPanel />}
              {activeTab === "layers" && <LayerPanel />}
              {activeTab === "models" && <ModelPanel />}
              {activeTab === "admin" && isAdmin && <AdminPanel />}
            </ErrorBoundary>
          </div>
        </nav>
        <main className="stage">
          <ErrorBoundary name="3D 지도">
            <TwinMap sensors={readings} onReady={mapHandle.set} />
          </ErrorBoundary>
          <ErrorBoundary name="건물 상세">
            <BuildingPanel />
          </ErrorBoundary>
          <ErrorBoundary name="AI 코파일럿">
            <CopilotChat />
          </ErrorBoundary>
        </main>
      </div>
      <Toasts />
    </div>
  );
}

function UserMenu() {
  const username = useSession((s) => s.username);
  const roles = useSession((s) => s.roles);
  const top = ["ADMIN", "PLANNER", "ANALYST", "VIEWER"].find((r) => roles.includes(r));
  return (
    <div className="user-menu">
      <span className="user-name" title={roles.join(", ")}>
        {username}
        {top && <span className="role-badge">{ROLE_LABEL[top]}</span>}
      </span>
      {username !== "dev" && (
        <button type="button" className="link-btn small" onClick={() => void logout()}>
          로그아웃
        </button>
      )}
    </div>
  );
}
