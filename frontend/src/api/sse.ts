// fetch 스트림용 SSE 파서. EventSource는 POST·헤더를 못 쓰므로 에이전트 대화에는 이 파서를 쓴다.

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

/** POST 요청의 SSE 응답을 이벤트 단위로 소비한다. AbortSignal로 취소할 수 있다. */
export async function postSse(
  url: string,
  body: unknown,
  onEvent: (e: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`요청 실패 (${res.status})`);
  }
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  const parser = new SseParser();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    for (const e of parser.push(value)) onEvent(e);
  }
}
