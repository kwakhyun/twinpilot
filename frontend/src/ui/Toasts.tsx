import { useTwin } from "../state/store";

/** 작업 결과·오류 알림. 스크린 리더가 읽도록 live region으로 둔다. */
export function Toasts() {
  const toasts = useTwin((s) => s.toasts);
  const dismiss = useTwin((s) => s.dismissToast);
  return (
    <div className="toasts" aria-live="polite" aria-atomic="false">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`} role={t.kind === "error" ? "alert" : "status"}>
          <span>{t.text}</span>
          <button type="button" className="icon-btn" aria-label="알림 닫기" onClick={() => dismiss(t.id)}>
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
