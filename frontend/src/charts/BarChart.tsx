import { useId, useRef, useState } from "react";

export interface BarDatum {
  key: string;
  label: string;
  value: number;
  color?: string;
}

interface Props {
  title: string;
  data: BarDatum[];
  unit?: string;
  orientation?: "vertical" | "horizontal";
  /** 막대 위에 값을 표시할 항목(선택적 라벨: 최댓값 등). */
  labelKeys?: string[];
}

const WIDTH = 300;
const BAR_MAX = 24;
const GAP = 2;
const RADIUS = 4;

/** 막대 끝 4px 라운드, 기준선 쪽은 직각. */
function columnPath(x: number, y: number, w: number, h: number): string {
  if (h <= 0) return "";
  const r = Math.min(RADIUS, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

function barPath(x: number, y: number, w: number, h: number): string {
  if (w <= 0) return "";
  const r = Math.min(RADIUS, h / 2, w);
  return `M${x},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h - r}Q${x + w},${y + h} ${x + w - r},${y + h}H${x}Z`;
}

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
}

const fmt = (v: number) => v.toLocaleString("ko-KR");

/**
 * 단일 계열 막대 차트(SVG). 단일 계열이라 범례 박스 없이 제목이 계열을 설명한다.
 * 막대마다 hover/focus 툴팁, 표 보기 전환을 제공한다.
 */
export function BarChart({ title, data, unit = "", orientation = "vertical", labelKeys = [] }: Props) {
  // 툴팁은 포인터가 아니라 막대 끝(viewBox 좌표)에 고정해 hover와 키보드 focus가 같은 위치에 뜨게 한다.
  const [hover, setHover] = useState<{ d: BarDatum; x: number; y: number } | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const [table, setTable] = useState(false);
  const titleId = useId();
  const max = niceMax(Math.max(0, ...data.map((d) => d.value)));
  const ticks = [0, max / 2, max];

  let svg: React.ReactNode;
  if (orientation === "vertical") {
    const H = 150;
    const top = 18;
    const left = 34;
    const plotW = WIDTH - left - 4;
    const band = plotW / Math.max(1, data.length);
    const bw = Math.min(BAR_MAX, band - GAP);
    const y = (v: number) => top + (H - top) * (1 - v / max);
    svg = (
      <svg viewBox={`0 0 ${WIDTH} ${H + 22}`} role="img" aria-labelledby={titleId} className="chart-svg">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={left} x2={WIDTH} y1={y(t)} y2={y(t)} className={t === 0 ? "axis" : "grid"} />
            <text x={left - 6} y={y(t) + 4} textAnchor="end" className="tick">
              {fmt(t)}
            </text>
          </g>
        ))}
        {data.map((d, i) => {
          const x = left + i * band + (band - bw) / 2;
          const h = H - y(d.value);
          return (
            <g
              key={d.key}
              tabIndex={0}
              className="bar"
              onPointerEnter={() => setHover({ d, x: x + bw / 2, y: y(d.value) })}
              onPointerLeave={() => setHover(null)}
              onFocus={() => setHover({ d, x: x + bw / 2, y: y(d.value) })}
              onBlur={() => setHover(null)}
              aria-label={`${d.label}: ${fmt(d.value)}${unit}`}
            >
              <rect x={left + i * band} y={top} width={band} height={H - top} fill="transparent" />
              <path d={columnPath(x, y(d.value), bw, h)} fill={d.color ?? "var(--series-1)"} />
              {labelKeys.includes(d.key) && (
                <text x={x + bw / 2} y={y(d.value) - 5} textAnchor="middle" className="value">
                  {fmt(d.value)}
                </text>
              )}
              <text x={x + bw / 2} y={H + 15} textAnchor="middle" className="tick">
                {d.label}
              </text>
            </g>
          );
        })}
      </svg>
    );
  } else {
    const rowH = 22;
    const labelW = 78;
    const H = data.length * rowH + 4;
    const plotW = WIDTH - labelW - 44;
    const x = (v: number) => (plotW * v) / max;
    svg = (
      <svg viewBox={`0 0 ${WIDTH} ${H}`} role="img" aria-labelledby={titleId} className="chart-svg">
        <line x1={labelW} x2={labelW} y1={0} y2={H} className="axis" />
        {data.map((d, i) => {
          const bh = Math.min(BAR_MAX, rowH - GAP - 6);
          const by = i * rowH + (rowH - bh) / 2;
          return (
            <g
              key={d.key}
              tabIndex={0}
              className="bar"
              onPointerEnter={() => setHover({ d, x: labelW + x(d.value), y: by })}
              onPointerLeave={() => setHover(null)}
              onFocus={() => setHover({ d, x: labelW + x(d.value), y: by })}
              onBlur={() => setHover(null)}
              aria-label={`${d.label}: ${fmt(d.value)}${unit}`}
            >
              <rect x={0} y={i * rowH} width={WIDTH} height={rowH} fill="transparent" />
              <text x={labelW - 6} y={by + bh / 2 + 4} textAnchor="end" className="tick">
                {d.label}
              </text>
              <path d={barPath(labelW, by, x(d.value), bh)} fill={d.color ?? "var(--series-1)"} />
              <text x={labelW + x(d.value) + 5} y={by + bh / 2 + 4} className="value">
                {fmt(d.value)}
              </text>
            </g>
          );
        })}
      </svg>
    );
  }

  return (
    <figure className="chart">
      <figcaption>
        <span id={titleId}>{title}</span>
        <button type="button" className="link-btn" onClick={() => setTable((t) => !t)} aria-pressed={table}>
          {table ? "차트 보기" : "표 보기"}
        </button>
      </figcaption>
      {table ? (
        <table className="data-table">
          <thead>
            <tr>
              <th>구간</th>
              <th className="num">값{unit && ` (${unit})`}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.key}>
                <td>{d.label}</td>
                <td className="num">{fmt(d.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="chart-body" ref={bodyRef}>
          {svg}
          {hover && (
            <div
              className="chart-tooltip"
              style={{
                left: Math.min(hover.x, WIDTH - 70) * ((bodyRef.current?.clientWidth ?? WIDTH) / WIDTH),
                top: Math.max(0, hover.y * ((bodyRef.current?.clientWidth ?? WIDTH) / WIDTH) - 46),
              }}
            >
              <strong>
                {fmt(hover.d.value)}
                {unit}
              </strong>
              <span>{hover.d.label}</span>
            </div>
          )}
        </div>
      )}
    </figure>
  );
}
