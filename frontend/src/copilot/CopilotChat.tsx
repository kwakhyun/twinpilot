import { useEffect, useRef, useState } from "react";
import { postSse } from "../api/sse";
import { useTwin } from "../state/store";
import { applyUiAction, describeAction, type UiAction } from "./actions";

interface Step {
  id: string;
  name: string;
  args: string;
  summary?: string;
  ok?: boolean;
}

interface Message {
  role: "user" | "assistant";
  content: string;
  steps?: Step[];
  actions?: string[];
  mode?: string;
  error?: string;
  pending?: boolean;
}

/** crypto.randomUUID는 보안 컨텍스트(HTTPS·localhost)에서만 있으므로 대체 경로를 둔다. */
function newSessionId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

const EXAMPLES = [
  "다솜동에서 소화전 사각지대에 있는 30년 이상 노후 건물을 위험도순으로 보여줘",
  "한빛동에 AED 3대를 추가한다면 어디가 좋을까?",
  "지금 수압 경보가 난 소화전이 있어?",
  "행정동별 화재 위험 현황을 요약해줘",
];

/** AI 코파일럿: 에이전트 SSE(tool_call/tool_result/ui_action/token/message/done)를 실시간으로 그린다. */
export function CopilotChat() {
  const [open, setOpen] = useState(true);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  // 대화 기록은 에이전트 서버의 체크포인터가 세션별로 이어 붙여 보관한다(append-only).
  const sessionRef = useRef<string>(newSessionId());
  const listRef = useRef<HTMLDivElement>(null);
  const prompt = useTwin((s) => s.copilotPrompt);
  const selectedBuildingId = useTwin((s) => s.selectedBuildingId);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  useEffect(() => {
    if (prompt) {
      setOpen(true);
      void send(prompt.text);
    }
    // 프롬프트 키가 바뀔 때만 실행한다.
  }, [prompt?.key]);

  function patchLast(fn: (m: Message) => Message) {
    setMessages((ms) => [...ms.slice(0, -1), fn(ms[ms.length - 1])]);
  }

  async function send(text: string) {
    const message = text.trim();
    if (!message || busy) return;
    setMessages((ms) => [...ms, { role: "user", content: message }, { role: "assistant", content: "", steps: [], actions: [], pending: true }]);
    setInput("");
    setBusy(true);
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    try {
      await postSse(
        "/agent/chat",
        { message, sessionId: sessionRef.current, context: { selectedBuildingId } },
        (e) => {
          const data = JSON.parse(e.data);
          switch (e.event) {
            case "tool_call":
              patchLast((m) => ({ ...m, steps: [...(m.steps ?? []), { id: data.id, name: data.name, args: JSON.stringify(data.args) }] }));
              break;
            case "tool_result":
              patchLast((m) => ({
                ...m,
                steps: (m.steps ?? []).map((s) => (s.id === data.id ? { ...s, summary: data.summary, ok: data.ok } : s)),
              }));
              break;
            case "ui_action": {
              const st = useTwin.getState();
              if (applyUiAction(data, st)) {
                patchLast((m) => ({ ...m, actions: [...(m.actions ?? []), describeAction(data as UiAction)] }));
              }
              break;
            }
            case "token":
              patchLast((m) => ({ ...m, content: m.content + data.text }));
              break;
            case "message":
              patchLast((m) => ({ ...m, content: data.content }));
              break;
            case "done":
              patchLast((m) => ({ ...m, mode: data.mode === "llm" ? `LLM · ${data.model}` : "규칙 기반 플래너(오프라인)", pending: false }));
              break;
            case "error":
              patchLast((m) => ({ ...m, error: data.message, pending: false }));
              break;
          }
        },
        ctrl.signal,
      );
    } catch (e) {
      if ((e as Error).name !== "AbortError") {
        patchLast((m) => ({ ...m, error: `에이전트 연결 실패: ${(e as Error).message}`, pending: false }));
      }
    } finally {
      patchLast((m) => ({ ...m, pending: false }));
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="copilot-fab" onClick={() => setOpen(true)}>
        AI 코파일럿
      </button>
    );
  }

  return (
    <section className="copilot" aria-label="AI 코파일럿">
      <header>
        <strong>AI 코파일럿</strong>
        <span className="muted small">LangGraph · MCP 도구</span>
        <button
          type="button"
          className="link-btn small"
          disabled={busy}
          onClick={() => {
            sessionRef.current = newSessionId();
            setMessages([]);
          }}
        >
          새 대화
        </button>
        <button type="button" className="icon-btn" aria-label="접기" onClick={() => setOpen(false)}>
          –
        </button>
      </header>
      <div className="copilot-list" ref={listRef} aria-live="polite">
        {messages.length === 0 && (
          <div className="examples">
            <p className="muted small">자연어로 질문하면 에이전트가 분석 도구를 호출하고 결과를 지도에 표시합니다.</p>
            {EXAMPLES.map((q) => (
              <button key={q} type="button" className="example" onClick={() => void send(q)}>
                {q}
              </button>
            ))}
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role}`}>
            {m.steps && m.steps.length > 0 && (
              <ol className="steps">
                {m.steps.map((s) => (
                  <li key={s.id} className={s.ok === false ? "fail" : ""}>
                    <code>{s.name}</code>
                    <span className="args">{s.args}</span>
                    {s.summary ? <span className="summary">→ {s.summary}</span> : <span className="summary">실행 중…</span>}
                  </li>
                ))}
              </ol>
            )}
            {m.content && <div className="content">{m.content}</div>}
            {m.pending && !m.content && <div className="content muted">생각 중…</div>}
            {m.actions && m.actions.length > 0 && (
              <div className="chips">
                {m.actions.map((a, j) => (
                  <span key={j} className="chip">
                    🗺 {a}
                  </span>
                ))}
              </div>
            )}
            {m.error && <div className="error-text">{m.error}</div>}
            {m.mode && <div className="muted small">{m.mode}</div>}
          </div>
        ))}
      </div>
      <form
        className="copilot-input"
        onSubmit={(e) => {
          e.preventDefault();
          void send(input);
        }}
      >
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="예: 누리동 소화전 커버리지는?"
          aria-label="코파일럿에게 질문"
          maxLength={500}
        />
        {busy ? (
          <button type="button" className="btn" onClick={() => abortRef.current?.abort()}>
            중지
          </button>
        ) : (
          <button type="submit" className="btn primary" disabled={!input.trim()}>
            보내기
          </button>
        )}
      </form>
    </section>
  );
}
