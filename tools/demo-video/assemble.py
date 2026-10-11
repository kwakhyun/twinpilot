"""녹화 프레임 + 마커로 최종 영상을 만든다(가변 간격 프레임 → 30fps, ASS 자막·장면 제목 합성).

    python3 assemble.py <take 폴더> <출력.mp4> [crf]
"""
import json
import subprocess
import sys
from pathlib import Path

take = Path(sys.argv[1])
out = Path(sys.argv[2])
crf = sys.argv[3] if len(sys.argv) > 3 else "22"
frames = json.loads((take / "frames.json").read_text())
markers = json.loads((take / "markers.json").read_text())

# 녹화 구간(resume~pause)만 이어 붙인 출력 시간축
intervals, start = [], None
for m in markers:
    if m["type"] == "resume":
        start = m["t"]
    elif m["type"] == "pause" and start is not None:
        intervals.append((start, m["t"]))
        start = None


# hold(보여 주는 대기)는 실제 속도, act(조작·로딩)는 SPEED배 빠르게
SPEED = float(__import__("os").environ.get("SPEED", "2.5"))
segments, rec_on, mode, seg_start = [], False, "act", None
for m in markers:
    k = m["type"]
    if k not in ("resume", "pause", "hold", "act"):
        continue
    if rec_on and seg_start is not None:
        segments.append((seg_start, m["t"], 1.0 if mode == "hold" else 1.0 / SPEED))
    if k == "resume":
        rec_on = True
    elif k == "pause":
        rec_on = False
    else:
        mode = k
    seg_start = m["t"] if rec_on else None


def out_t(t: float) -> float:
    acc = 0.0
    for a, b, rate in segments:
        if t <= a:
            break
        acc += (min(t, b) - a) * rate
    return acc


def interval_end(t: float) -> float:
    for a, b in intervals:
        if a - 0.05 <= t <= b:
            return b
    return t


total = out_t(intervals[-1][1])

# 프레임 길이: 다음 프레임까지(같은 녹화 구간 안), 구간의 마지막 프레임은 구간 끝까지
lines, prev_out = [], 0.0
kept = [f for f in frames if any(a - 0.05 <= f["t"] <= b for a, b in intervals)]
for i, f in enumerate(kept):
    s = out_t(f["t"])
    nxt = kept[i + 1]["t"] if i + 1 < len(kept) else None
    end_t = interval_end(f["t"])
    e = out_t(min(nxt, end_t)) if nxt is not None and nxt <= end_t else out_t(end_t)
    if i + 1 < len(kept) and nxt is not None and nxt > end_t:
        e = out_t(nxt)          # 다음 구간 첫 프레임 전까지 화면 유지
    dur = max(e - s, 0.001)
    lines.append(f"file '{(take / 'frames' / f['f']).as_posix()}'\nduration {dur:.4f}")
lines.append(f"file '{(take / 'frames' / kept[-1]['f']).as_posix()}'")
(take / "concat.txt").write_text("\n".join(lines) + "\n")


def ts(sec: float) -> str:
    sec = max(sec, 0)
    h, r = divmod(sec, 3600)
    m, s = divmod(r, 60)
    return f"{int(h)}:{int(m):02d}:{s:05.2f}"


# 자막(하단)·장면 제목(좌상단). 다음 같은 종류 마커나 인트로·아웃트로 경계에서 끝난다.
events = []
bounds = [out_t(m["t"]) for m in markers if m["type"] in ("section", "end")]


def until(kind: str, idx: int, s: float) -> float:
    for m in markers[idx + 1:]:
        if m["type"] == kind:
            return out_t(m["t"])
    return min([b for b in bounds if b > s + 0.01] + [total])


for i, m in enumerate(markers):
    if m["type"] not in ("sub", "title"):
        continue
    s = out_t(m["t"])
    e = min(until(m["type"], i, s), min([b for b in bounds if b > s + 0.01] + [total]))
    if e - s < 0.3:
        continue
    if m["type"] == "sub":
        events.append(f"Dialogue: 1,{ts(s)},{ts(e)},Sub,,0,0,0,,{{\\fad(180,180)}}{m['text']}")
    else:
        events.append(f"Dialogue: 2,{ts(s)},{ts(e)},Title,,0,0,0,,{{\\fad(250,250)}}{m['text']}")

ass = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,Pretendard SemiBold,40,&H00FFFFFF,&H00FFFFFF,&H40101418,&H40101418,0,0,0,0,100,100,0,0,3,14,0,2,140,140,56,1
Style: Title,Pretendard ExtraBold,34,&H00FFFFFF,&H00FFFFFF,&H00E08C3B,&H00E08C3B,0,0,0,0,100,100,0,0,3,12,0,7,470,60,96,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
""" + "\n".join(events) + "\n"
(take / "subs.ass").write_text(ass, encoding="utf-8")

fade_out = max(total - 0.9, 0)
vf = (f"fps=30,scale=1920:1080:flags=lanczos,format=yuv420p,"
      f"ass={take / 'subs.ass'}:fontsdir={Path.home() / '.fonts'},"
      f"fade=t=in:st=0:d=0.6,fade=t=out:st={fade_out:.2f}:d=0.9")
cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(take / "concat.txt"),
       "-vf", vf, "-c:v", "libx264", "-preset", "slow", "-crf", crf, "-tune", "animation", "-movflags", "+faststart",
       "-t", f"{total:.2f}", str(out)]
subprocess.run(cmd, check=True)
print(f"길이 {total:.1f}초, 자막·제목 {len(events)}개, 프레임 {len(kept)}개 → {out}")
