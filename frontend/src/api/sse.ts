// fetch 스트림용 SSE. EventSource는 Authorization 헤더·POST를 쓸 수 없으므로 모든 스트림에 이 구현을 쓴다.
import { authFetch, errorMessage } from "./http";

export interface SseEvent {
  event: string;
  data: string;
}

/** 텍스트 조각을 받아 완성된 이벤트만 돌려주는 증분 파서(줄바꿈 \n, \r\n 모두 지원). */
export class SseParser {
  private buffer = "";

  push(chunk: string): SseEvent[] {
    this.buffer += chunk.replace(/\r\n/g, "\n");
    const events: SseEvent[] = [];
    let sep: number;
    while ((sep = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, sep);
      this.buffer = this.buffer.slice(sep + 2);
      const parsed = parseBlock(block);
      if (parsed) events.push(parsed);
    }
    return events;
  }
}

function parseBlock(block: string): SseEvent | null {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue;
    const idx = line.indexOf(":");
    const field = idx < 0 ? line : line.slice(0, idx);
    let value = idx < 0 ? "" : line.slice(idx + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") event = value;
    else if (field === "data") data.push(value);
  }
  if (data.length === 0) return null;
  return { event, data: data.join("\n") };
}

export class HttpError extends Error {
  constructor(
    public status: number,
    message: string,
    public retryAfter?: number,
  ) {
    super(message);
  }
}

/** 요청 하나의 SSE 응답을 끝까지 소비한다. AbortSignal로 취소할 수 있다. */
export async function streamSse(
  url: string,
  init: RequestInit,
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "text/event-stream");
  const res = await authFetch(url, { ...init, headers });
  if (!res.ok || !res.body) {
    const retry = Number(res.headers.get("Retry-After") ?? "") || undefined;
    throw new HttpError(res.status, await errorMessage(res), retry);
  }
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  const parser = new SseParser();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    for (const e of parser.push(value)) onEvent(e);
  }
}

export async function postSse(url: string, body: unknown, onEvent: (e: SseEvent) => void, signal?: AbortSignal) {
  return streamSse(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal }, onEvent);
}

/**
 * 끊기면 지수 백오프(최대 30초)로 다시 연결하는 GET 스트림. 반환값을 호출하면 구독을 끝낸다.
 * shouldStop이 true를 돌려주면(예: Job 최종 이벤트) 재연결하지 않는다.
 */
export function subscribeSse(
  url: string,
  handlers: {
    onEvent: (e: SseEvent) => void;
    onStatus?: (connected: boolean) => void;
    shouldStop?: () => boolean;
  },
): () => void {
  const ctrl = new AbortController();
  let delay = 1000;
  void (async () => {
    while (!ctrl.signal.aborted) {
      try {
        await streamSse(url, { signal: ctrl.signal }, (e) => {
          delay = 1000;
          handlers.onStatus?.(true);
          handlers.onEvent(e);
        });
      } catch (e) {
        if (ctrl.signal.aborted) return;
        if (e instanceof HttpError && (e.status === 403 || e.status === 404)) {
          handlers.onStatus?.(false);
          return; // 다시 시도해도 같은 결과
        }
      }
      handlers.onStatus?.(false);
      if (handlers.shouldStop?.() || ctrl.signal.aborted) return;
      await new Promise((r) => setTimeout(r, delay + Math.random() * 500));
      delay = Math.min(delay * 2, 30_000);
    }
  })();
  return () => ctrl.abort();
}
