"""규칙 기반 플래너(LLM 없이 동작하는 폴백).

LLM 노드와 같은 계약을 따른다: 사용자 메시지를 받으면 도구 호출(AIMessage.tool_calls)을 만들고,
도구 결과(ToolMessage)를 받으면 최종 답변을 만든다. 덕분에 API 키가 없어도
LangGraph 그래프 → MCP 클라이언트 → MCP 서버 → twin-api 전체 경로를 시연·테스트할 수 있다.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, ToolMessage

FACILITY_PATTERNS = [
    ("AED", re.compile(r"AED|제세동|심장충격", re.I)),
    ("FIRE_STATION", re.compile(r"소방서|골든\s*타임|출동|도달\s*시간")),
    ("HYDRANT", re.compile(r"소화전|소방용수")),
]
REGION_RE = re.compile(r"([가-힣]{2,5}동)(?=에서|의|에|은|는|을|를|이|가|로|\s|$|,|\.|\?)")
NOT_REGION = {"행정동", "자동", "이동", "출동", "활동", "작동", "연동", "변동"}


@dataclass
class Intent:
    tool: str
    args: dict[str, Any] = field(default_factory=dict)


def _facility(text: str) -> str | None:
    for code, pattern in FACILITY_PATTERNS:
        if pattern.search(text):
            return code
    return None


def _region(text: str) -> str | None:
    for m in REGION_RE.finditer(text):
        name = m.group(1)
        if name not in NOT_REGION and not re.match(r"^\d", name):
            return name
    return None


def _int(pattern: str, text: str) -> int | None:
    m = re.search(pattern, text)
    return int(m.group(1)) if m else None


def plan(text: str, context: dict[str, Any] | None = None) -> list[Intent]:
    """질문 하나를 0개 이상의 도구 호출로 바꾼다. 독립적인 의도는 함께(병렬) 호출한다."""
    t = text.strip()
    region = _region(t)
    facility = _facility(t)
    intents: list[Intent] = []

    building_id = _int(r"건물\s*#?\s*(\d+)\s*번?", t) or _int(r"#(\d+)", t)
    if building_id is None and context and context.get("selectedBuildingId") and re.search(r"이 건물|선택한 건물|선택된 건물", t):
        building_id = int(context["selectedBuildingId"])
    if building_id is not None:
        intents.append(Intent("get_building_detail", {"building_id": building_id}))

    response_time = re.search(r"출동\s*시간|도착\s*시간|골든\s*타임|도달\s*시간|도달권|몇\s*분\s*(?:안|만|이내)", t)
    if response_time and building_id is None:
        args = {}
        if region:
            args["region"] = region
        if (minutes := _int(r"(\d+)\s*분", t)) is not None and 1 <= minutes <= 30:
            args["target_min"] = minutes
        intents.append(Intent("analyze_response_time", args))
    elif re.search(r"추천|어디(가|에)?|입지|설치(하|할)|추가(하|로|한다면|할)|늘리", t) and facility in ("HYDRANT", "AED"):
        args: dict[str, Any] = {"facility_type": facility, "count": _int(r"(\d+)\s*(?:대|개|곳|기)", t) or 3}
        if region:
            args["region"] = region
        intents.append(Intent("recommend_facility_sites", args))
    elif re.search(r"건물", t) and building_id is None and re.search(
            r"사각지대|밖|미커버|노후|오래된|위험|년\s*이상|층\s*이상|고층", t):
        args = {"sort": "risk", "limit": 10}
        if region:
            args["region"] = region
        if (age := _int(r"(\d+)\s*년\s*(?:이상|넘|초과|된|지난)", t)) is not None:
            args["min_age"] = age
        elif re.search(r"노후|오래된", t):
            args["min_age"] = 30
        if (floors := _int(r"(\d+)\s*층\s*(?:이상|넘)", t)) is not None:
            args["min_floors"] = floors
        if (risk := _int(r"위험도\s*(\d+)", t)) is not None:
            args["min_risk"] = risk
        if re.search(r"사각지대|밖|미커버|커버되지|닿지", t):
            args["uncovered_by"] = facility or "HYDRANT"
        if re.search(r"오래된\s*순|노후\s*순|연식\s*순", t):
            args["sort"] = "age"
        intents.append(Intent("search_buildings", args))
    elif re.search(r"커버리지|사각지대|반경|커버|도달", t) or (facility and re.search(r"분석|현황|상태|얼마", t)):
        args = {"facility_type": facility or "HYDRANT"}
        if region:
            args["region"] = region
        if (radius := _int(r"(\d+)\s*(?:m|미터)", t)) is not None:
            args["radius_m"] = radius
        if re.search(r"계획|배치한", t):
            args["include_planned"] = True
        intents.append(Intent("analyze_coverage", args))

    if re.search(r"센서|수압|경보|공기질|미세먼지|PM\s*2\.?5|IoT", t, re.I):
        args = {"alerts_only": bool(re.search(r"경보|이상|나쁨|문제|저하", t))}
        if re.search(r"수압|소화전", t):
            args["kind"] = "HYDRANT_PRESSURE"
        elif re.search(r"공기질|미세먼지|PM|버스", t, re.I):
            args["kind"] = "AIR_QUALITY"
        intents.append(Intent("get_sensor_status", args))

    if re.search(r"현황|요약|통계|대시보드|전반|브리핑", t) and not any(
            i.tool in ("analyze_coverage", "search_buildings", "get_building_detail", "analyze_response_time")
            for i in intents):
        intents.append(Intent("get_dashboard_stats"))
    if re.search(r"3D\s*모델|glTF|GLB|모델\s*목록", t, re.I):
        intents.append(Intent("list_3d_models"))
    if re.search(r"행정동\s*(목록|리스트)|어떤\s*(지역|동)", t):
        intents.append(Intent("list_regions"))
    return intents


HELP = ("지금은 다음과 같은 질문에 답할 수 있습니다.\n"
        "· 소화전/AED/소방서 커버리지와 사각지대 (예: 다솜동 소화전 커버리지는?)\n"
        "· 조건별 건물 검색 (예: 소화전 사각지대의 30년 이상 노후 건물)\n"
        "· 신규 시설 최적 입지 추천 (예: 한빛동에 AED 3대를 추가한다면?)\n"
        "· 도로망 기준 소방서 출동 시간 (예: 한빛동 출동 시간 7분 안에 도착해?)\n"
        "· 건물 상세·화재 대응 브리핑 (예: 건물 696번 브리핑)\n"
        "· 센서 경보, 전체 현황 요약")


def message_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    return "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")


def _payload(msg: ToolMessage) -> tuple[str, Any]:
    text = message_text(msg.content)
    head, _, rest = text.partition("\n")
    try:
        return head, json.loads(rest) if rest else None
    except json.JSONDecodeError:
        return head, None


def _detail_lines(tool: str, data: Any) -> list[str]:
    if data is None:
        return []
    if tool == "search_buildings":
        return [f"  - #{b['id']} {b['name']} · {b['use']} · {b['floors']}층 · {b['builtYear']}년 · 위험도 {b['risk']}"
                + (f" · 최근접 소화전 {b['nearestHydrantM']}m" if b.get("nearestHydrantM") else "") for b in data[:5]]
    if tool == "analyze_coverage":
        lines = [f"  - {r['name']}: {r['coverageRate']}% (반경 {r['radiusM']:.0f}m, 미커버 고위험 {r['uncoveredHighRisk']}동)"
                 for r in data["regions"]]
        if data.get("topUncovered"):
            top = data["topUncovered"][0]
            lines.append(f"  - 우선 조치 1순위: #{top['id']} {top['name']} (위험도 {top['risk']}, 최근접 {top['nearestM']}m)")
        lines.append(f"  - 기준: {data['standard']}")
        return lines
    if tool == "recommend_facility_sites":
        return [f"  - 추천 {s['rank']}: {s['region']} ({s['lat']:.5f}, {s['lon']:.5f}) · 신규 커버 {s['newlyCoveredBuildings']}동"
                for s in data["sites"]] + [f"  - 방법: {data['method']}"]
    if tool == "get_building_detail":
        f = data.get("riskFactors") or {}
        lines = [f"  - {data['use']} · {data['structure']} · 지상 {data['floors']}층/지하 {data['basementFloors']}층 · "
                 f"{data['builtYear']}년 · 위험도 {data['risk']}",
                 f"  - 위험 요인: 노후 {f.get('age')} · 구조 {f.get('structure')} · 층수 {f.get('height')} · "
                 f"소화전 거리 {f.get('hydrant')} · 소방서 도달 {f.get('response')}"]
        if data.get("responseMin") is not None:
            ok = "충족" if data.get("responseWithinTarget") else "미달"
            how = "도로망 기준" if "도로망" in (data.get("responseMethod") or "") else "직선거리 근사"
            lines.append(f"  - {data['fireStation']} 출동 {data['responseMin']}분({how}, 목표 7분 {ok})")
        ladder = data.get("ladder") or {}
        if ladder.get("floorsBeyondReach"):
            lines.append(f"  - 사다리차({ladder['ladderReachM']}m) 도달 불가: {ladder['floorsBeyondReach']}개 층")
        if data.get("nearestHydrants"):
            h = data["nearestHydrants"][0]
            lines.append(f"  - 최근접 소화전 {h['name']} {h['distanceM']}m")
        for s in data.get("hydrantSensors", []):
            if s["status"] == "ALERT":
                lines.append(f"  - ⚠ 인근 소화전 수압 저하: {s['code']} {s['pressureMpa']}MPa")
        return lines
    if tool == "analyze_response_time":
        lines = [f"  - 중앙값 {data['p50Min']}분, 90백분위 {data['p90Min']}분, 최장 {data['maxMin']}분"]
        lines += [f"  - {r['name']}: {r['withinRate']}% 도달(90백분위 {r['p90Min']}분)" for r in data["regions"]]
        if data.get("slowest"):
            b = data["slowest"][0]
            lines.append(f"  - 우선 검토: #{b['id']} {b['name']} (위험도 {b['risk']}, 출동 {b['responseMin']}분, {b['station']})")
        lines.append(f"  - 기준: {data['method']}")
        return lines
    if tool == "get_sensor_status":
        return [f"  - {s['code']} ({s['status']}): " + ", ".join(f"{k} {v}" for k, v in s["values"].items())
                for s in data[:5]]
    if tool == "get_dashboard_stats":
        t = data["totals"]
        lines = [f"  - 전체 {t['buildings']}동, 평균 위험도 {t['avgRisk']}, 고위험 {t['highRisk']}동, "
                 f"{data['oldAgeYears']}년 이상 노후 {t['oldRatio']}%",
                 f"  - 소화전 커버리지 {data['hydrantCoverage']}%, AED 커버리지 {data['aedCoverage']}%"]
        lines += [f"  - {r['name']}: 평균 위험도 {r['avgRisk']}, 고위험 {r['highRisk']}동, 노후 {r['oldRatio']}%"
                  for r in data["regions"]]
        return lines
    if tool in ("list_regions", "list_3d_models"):
        return [f"  - {json.dumps(r, ensure_ascii=False)}" for r in data[:8]]
    return []


def compose_answer(tool_messages: list[ToolMessage]) -> str:
    parts: list[str] = []
    errors = 0
    for m in tool_messages:
        head, data = _payload(m)
        if m.status == "error" or head.startswith("오류"):
            errors += 1
            parts.append(f"· {m.name}: {head}")
            continue
        parts.append(f"· {head}")
        try:
            parts.extend(_detail_lines(m.name or "", data))
        except (KeyError, TypeError, ValueError, AttributeError):
            pass  # 도구 결과 형식이 예상과 다르면 요약 줄만 쓴다
    if errors < len(tool_messages):
        parts.append("\n결과는 3D 지도에 표시했습니다.")
    parts.append("※ 합성(가상) 데모 데이터 기준이며 실제 건물·시설과 무관합니다.")
    return "\n".join(parts)


class RulePlanner:
    """LangGraph 노드로 쓰는 규칙 기반 의사결정기."""

    model_name = "rule-planner"

    def __call__(self, messages: list[AnyMessage], context: dict[str, Any] | None = None) -> AIMessage:
        last_human = max((i for i, m in enumerate(messages) if isinstance(m, HumanMessage)), default=-1)
        tool_results = [m for m in messages[last_human + 1:] if isinstance(m, ToolMessage)]
        if tool_results:
            return AIMessage(content=compose_answer(tool_results))
        question = message_text(messages[last_human].content) if last_human >= 0 else ""
        intents = plan(question, context)
        if not intents:
            return AIMessage(content=HELP)
        return AIMessage(content="", tool_calls=[
            {"name": i.tool, "args": i.args, "id": f"rule_{n}", "type": "tool_call"} for n, i in enumerate(intents)])
