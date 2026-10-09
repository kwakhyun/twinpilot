"""TwinPilot MCP 서버.

디지털트윈 공간 분석을 MCP 도구로 표준화한다. 같은 도구를 TwinPilot AI 에이전트(LangGraph)와
외부 MCP 클라이언트(Claude Desktop 등)가 함께 쓴다.

도구 결과 형식
- content(text): LLM이 읽는 요약 + 핵심 수치(JSON). 큰 ID 목록은 넣지 않는다.
- structuredContent: {"data": ..., "ui_actions": [...]} — 프론트엔드 지도 동작(하이라이트·이동 등)
"""
from __future__ import annotations

import functools
import json
import os
import time
from typing import Annotated, Any, Literal

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import Context, FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import CallToolResult, TextContent, ToolAnnotations
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import Field
from starlette.requests import Request
from starlette.responses import PlainTextResponse, Response

from .auth import AuthConfig, JwtVerifier, UpstreamTokens
from .backend import BackendError, TwinApi
from .telemetry import TOOL_CALLS, TOOL_LATENCY

FacilityType = Literal["HYDRANT", "AED", "FIRE_STATION"]
FACILITY_KO = {"HYDRANT": "소화전", "AED": "AED", "FIRE_STATION": "소방서"}
USE_KO = {
    "RESIDENTIAL_APT": "공동주택", "RESIDENTIAL_HOUSE": "단독·다가구", "COMMERCIAL": "근린상업", "OFFICE": "업무",
    "INDUSTRIAL": "공장", "WAREHOUSE": "창고", "PUBLIC": "공공", "SCHOOL": "학교",
}
STRUCTURE_KO = {"RC": "철근콘크리트", "STEEL": "철골", "MASONRY": "조적", "WOOD": "목조"}
READ_ONLY = ToolAnnotations(readOnlyHint=True, openWorldHint=False)
DISCLAIMER = "※ 합성(가상) 데모 데이터 기준이며 실제 건물·시설과 무관합니다."

INSTRUCTIONS = """TwinPilot 디지털트윈 공간 분석 서버입니다.
행정동 이름(예: 다솜동) 또는 ID로 지역을 지정할 수 있습니다.
수치는 반드시 도구 결과에서 인용하고, 분석 기준(twin://standards)을 함께 안내하세요."""


def _transport_security() -> TransportSecuritySettings | None:
    hosts = [h.strip() for h in os.getenv("MCP_ALLOWED_HOSTS", "").split(",") if h.strip()]
    if not hosts:
        return None
    return TransportSecuritySettings(enable_dns_rebinding_protection=True, allowed_hosts=hosts,
                                     allowed_origins=[f"http://{h}" for h in hosts])


def instrumented(fn):
    """도구 호출 수·결과·지연 시간을 Prometheus 메트릭으로 남긴다(서명은 그대로 유지)."""
    @functools.wraps(fn)
    async def wrapper(*args, **kwargs):
        started = time.perf_counter()
        outcome = "exception"
        try:
            res = await fn(*args, **kwargs)
            outcome = "error" if getattr(res, "isError", False) else "ok"
            return res
        finally:
            TOOL_CALLS.labels(fn.__name__, outcome).inc()
            TOOL_LATENCY.labels(fn.__name__).observe(time.perf_counter() - started)
    return wrapper


def create_server(api: TwinApi | None = None, host: str = "127.0.0.1", port: int = 8090,
                  auth: AuthConfig | None = None, verifier: JwtVerifier | None = None,
                  upstream: UpstreamTokens | None = None) -> FastMCP:
    """auth가 있으면 OAuth 보호 리소스로 동작하고, twin-api 호출에는 사용자별 교환 토큰을 쓴다."""
    api = api or TwinApi()
    auth_kwargs: dict[str, Any] = {}
    if auth is not None:
        verifier = verifier or JwtVerifier(auth.issuer, auth.jwks_url, auth.audience)
        upstream = upstream or UpstreamTokens(auth)
        auth_kwargs = {
            "token_verifier": verifier,
            "auth": AuthSettings(issuer_url=auth.issuer, resource_server_url=auth.resource_url,
                                 validate_token_resource=False),   # 대상(aud)은 JwtVerifier가 직접 검증
        }

        async def token_provider() -> str | None:
            access = get_access_token()
            if access is not None:
                return await upstream.for_user(access.token)
            if auth.client_secret:
                return await upstream.service()   # stdio 등 사용자 컨텍스트가 없는 실행
            return None

        def on_unauthorized() -> None:
            access = get_access_token()
            upstream.invalidate(access.token if access else None)

        api.token_provider = token_provider
        api.on_unauthorized = on_unauthorized

    mcp = FastMCP(
        "twinpilot",
        instructions=INSTRUCTIONS,
        host=host,
        port=port,
        stateless_http=True,  # 세션 고정 없이 수평 확장(Kubernetes 다중 레플리카)
        transport_security=_transport_security(),
        **auth_kwargs,
    )

    def result(summary: str, data: Any, ui_actions: list[dict[str, Any]] | None = None,
               llm_data: Any = None) -> CallToolResult:
        text = summary if llm_data is None else f"{summary}\n{json.dumps(llm_data, ensure_ascii=False)}"
        return CallToolResult(content=[TextContent(type="text", text=text)],
                              structuredContent={"data": data, "ui_actions": ui_actions or []})

    def error(e: Exception) -> CallToolResult:
        return CallToolResult(content=[TextContent(type="text", text=f"오류: {e}")], isError=True)

    # ------------------------------------------------------------------ tools
    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def list_regions() -> CallToolResult:
        """행정동 목록과 용도지역, 소화전 법정 기준 반경, 건물 수를 조회한다."""
        try:
            regions = await api.regions()
        except BackendError as e:
            return error(e)
        rows = [{"id": r["id"], "name": r["name"], "zoneType": r["zoneType"],
                 "hydrantRadiusM": r["hydrantRadiusM"], "buildingCount": r["buildingCount"]} for r in regions]
        return result(f"행정동 {len(rows)}곳", rows, llm_data=rows)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def search_buildings(
        region: Annotated[str | None, Field(description="행정동 이름 또는 ID. 생략하면 전체")] = None,
        min_age: Annotated[int | None, Field(ge=0, le=150, description="준공 후 최소 경과연수(예: 30)")] = None,
        min_risk: Annotated[float | None, Field(ge=0, le=100, description="최소 화재위험도(0~100)")] = None,
        use_types: Annotated[list[str] | None, Field(description="용도 코드 목록: RESIDENTIAL_APT, RESIDENTIAL_HOUSE, "
                                                     "COMMERCIAL, OFFICE, INDUSTRIAL, WAREHOUSE, PUBLIC, SCHOOL")] = None,
        min_floors: Annotated[int | None, Field(ge=0, le=200)] = None,
        uncovered_by: Annotated[FacilityType | None, Field(description="이 시설의 기준 반경 밖(사각지대) 건물만")] = None,
        sort: Annotated[Literal["risk", "age", "floors", "height"], Field(description="정렬 기준")] = "risk",
        limit: Annotated[int, Field(ge=1, le=50, description="상세 목록 개수")] = 10,
    ) -> CallToolResult:
        """조건에 맞는 건물을 검색한다. 결과 전체는 지도에 하이라이트되고, 상위 limit개 상세를 돌려준다."""
        try:
            r = await api.resolve_region(region)
            params = {"regionId": r["id"] if r else None, "minAge": min_age, "minRisk": min_risk,
                      "useType": use_types, "minFloors": min_floors, "uncoveredBy": uncovered_by, "sort": sort}
            # 하이라이트용 ID는 넉넉히(최대 2000), LLM에는 상위 limit개만 전달
            full = await api.get("/api/v1/buildings/search", {**params, "limit": 2000})
        except BackendError as e:
            return error(e)
        items = full["items"][:limit]
        ids = [b["id"] for b in full["items"]]
        cond = ", ".join(filter(None, [
            r and r["name"], min_age and f"{min_age}년 이상", min_risk and f"위험도 {min_risk} 이상",
            uncovered_by and f"{FACILITY_KO[uncovered_by]} 사각지대", min_floors and f"{min_floors}층 이상"]))
        summary = (f"조건({cond or '전체'})에 맞는 건물 {full['total']}동, 평균 위험도 {full['avgRisk']}, "
                   f"최고 {full['maxRisk']}. 상위 {len(items)}동:")
        brief = [{"id": b["id"], "name": b["name"], "use": USE_KO.get(b["useType"], b["useType"]),
                  "floors": b["floors"], "builtYear": b["builtYear"], "risk": b["risk"],
                  "nearestHydrantM": (b.get("riskFactors") or {}).get("nearestHydrantM"),
                  "region": b["regionName"]} for b in items]
        ui = [{"type": "highlightBuildings", "ids": ids, "label": f"검색 결과: {cond or '전체'}"}]
        return result(summary, {"total": full["total"], "items": brief, "ids": ids}, ui, llm_data=brief)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def get_building_detail(building_id: Annotated[int, Field(ge=1)]) -> CallToolResult:
        """건물 상세: 위험도 요인, 최근접 소화전·AED, 소방서 도달 추정, 사다리차 도달 분석, 소화전 수압 센서."""
        try:
            d = await api.get(f"/api/v1/buildings/{building_id}")
        except BackendError as e:
            return error(e)
        b = d["building"]
        resp = d.get("response") or {}
        brief = {
            "id": b["id"], "name": b["name"], "use": USE_KO.get(b["useType"], b["useType"]),
            "structure": STRUCTURE_KO.get(b["structure"], b["structure"]),
            "floors": b["floors"], "basementFloors": d["basementFloors"], "heightM": b["heightM"],
            "builtYear": b["builtYear"], "region": b["regionName"], "risk": b["risk"], "riskFactors": b["riskFactors"],
            "nearestHydrants": [{"name": h["name"], "distanceM": h["distanceM"], "status": h["status"]}
                                for h in d["nearestHydrants"]],
            "nearestAeds": [{"name": a["name"], "distanceM": a["distanceM"]} for a in d["nearestAeds"]],
            "fireStation": resp.get("stationName"), "responseMin": resp.get("estimatedMinutes"),
            "responseMethod": resp.get("method"), "responseWithinTarget": resp.get("withinTarget"),
            "roadAccessM": resp.get("roadAccessM"),
            "ladder": d["ladder"],
            "hydrantSensors": [{"code": s["code"], "pressureMpa": s["values"].get("pressureMpa"), "status": s["status"]}
                               for s in d["hydrantSensors"]],
        }
        ui = [{"type": "selectBuilding", "id": b["id"]}, {"type": "flyTo", "lon": b["lon"], "lat": b["lat"], "height": 350}]
        return result(f"건물 #{b['id']} {b['name']} 상세", brief, ui, llm_data=brief)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def analyze_coverage(
        facility_type: Annotated[FacilityType, Field(description="HYDRANT(소화전), AED, FIRE_STATION(소방서 골든타임)")],
        region: Annotated[str | None, Field(description="행정동 이름 또는 ID. 생략하면 전체")] = None,
        radius_m: Annotated[float | None, Field(gt=0, le=10000, description="반경 직접 지정. 생략하면 법정/기본 기준")] = None,
        include_planned: Annotated[bool, Field(description="계획(PLANNED) 시설 포함 여부")] = False,
    ) -> CallToolResult:
        """시설 커버리지와 사각지대를 분석한다(건물 외곽선-시설 간 수평거리 기준)."""
        try:
            r = await api.resolve_region(region)
            cov = await api.post("/api/v1/analysis/coverage", {
                "facilityType": facility_type, "regionId": r["id"] if r else None, "radiusM": radius_m,
                "includePlanned": include_planned, "includeBlindSpots": False, "topUncovered": 5})
        except BackendError as e:
            return error(e)
        s = cov["summary"]
        llm = {
            "standard": cov["standard"], "coverageRate": s["coverageRate"], "totalBuildings": s["totalBuildings"],
            "uncovered": s["totalBuildings"] - s["coveredBuildings"], "uncoveredHighRisk": s["uncoveredHighRisk"],
            "regions": [{"name": x["regionName"], "coverageRate": x["coverageRate"], "radiusM": x["radiusM"],
                         "uncoveredHighRisk": x["uncoveredHighRisk"]} for x in cov["regions"]],
            "topUncovered": [{"id": b["id"], "name": b["name"], "risk": b["risk"], "nearestM": b["nearestFacilityM"]}
                             for b in cov["topUncovered"]],
        }
        ui = [
            {"type": "runCoverage", "facilityType": facility_type, "regionId": r["id"] if r else None, "radiusM": radius_m},
            {"type": "highlightBuildings", "ids": cov["uncoveredBuildingIds"],
             "label": f"{FACILITY_KO[facility_type]} 미커버 건물"},
        ]
        summary = (f"{FACILITY_KO[facility_type]} 커버리지 {s['coverageRate']}% "
                   f"({r['name'] if r else '전체'}, 미커버 {llm['uncovered']}동, 미커버 고위험 {s['uncoveredHighRisk']}동)")
        return result(summary, cov | {"blindSpots": []}, ui, llm_data=llm)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def recommend_facility_sites(
        ctx: Context,
        facility_type: Annotated[Literal["HYDRANT", "AED"], Field(description="추가 설치할 시설")],
        count: Annotated[int, Field(ge=1, le=10, description="추가 설치 수")] = 3,
        region: Annotated[str | None, Field(description="행정동 이름 또는 ID. 생략하면 전체")] = None,
        radius_m: Annotated[float | None, Field(gt=0, le=5000)] = None,
    ) -> CallToolResult:
        """위험도 가중 최대 커버리지(MCLP) 기준으로 신규 시설 최적 입지를 추천한다(비동기 Job, 수 초 소요).
        시설을 실제로 배치하지는 않는다."""
        try:
            r = await api.resolve_region(region)
            job = await api.post("/api/v1/analysis/jobs", {
                "type": "RECOMMEND_SITES", "facilityType": facility_type, "count": count,
                "regionId": r["id"] if r else None, "radiusM": radius_m})

            async def progress(pct: int, msg: str) -> None:
                await ctx.report_progress(pct, 100, msg)

            done = await api.wait_job(job["id"], progress)
        except BackendError as e:
            return error(e)
        if done["status"] != "SUCCEEDED":
            return error(BackendError(done.get("error") or f"추천 {done['status']}"))
        res = done["result"]
        llm = {
            "coverageBefore": res["coverageBefore"], "coverageAfter": res["coverageAfter"],
            "newlyCovered": res["newlyCovered"], "method": res["method"],
            "sites": [{"rank": s["rank"], "region": s["regionName"], "lon": s["lon"], "lat": s["lat"],
                       "newlyCoveredBuildings": s["newlyCoveredBuildings"], "riskCovered": s["riskCovered"]}
                      for s in res["sites"]],
        }
        ui = [
            {"type": "showSites", "result": res},
            {"type": "highlightBuildings", "ids": [i for s in res["sites"] for i in s["buildingIds"]],
             "label": "추천 입지로 새로 커버되는 건물"},
        ]
        summary = (f"{FACILITY_KO[facility_type]} {len(res['sites'])}곳 추천: 커버리지 {res['coverageBefore']}% → "
                   f"{res['coverageAfter']}% (신규 {res['newlyCovered']}동)")
        return result(summary, res, ui, llm_data=llm)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def get_dashboard_stats() -> CallToolResult:
        """전체·행정동별 화재위험 통계, 노후 건물 비율, 소화전·AED 커버리지 요약."""
        try:
            s = await api.get("/api/v1/stats/summary")
        except BackendError as e:
            return error(e)
        llm = {
            "totals": s["totals"], "highRiskThreshold": s["highRiskThreshold"], "oldAgeYears": s["oldAgeYears"],
            "hydrantCoverage": s["hydrantCoverage"]["coverageRate"], "aedCoverage": s["aedCoverage"]["coverageRate"],
            "regions": s["regions"],
        }
        return result(f"건물 {s['totals']['buildings']}동 통계", s, [{"type": "setColorMode", "mode": "risk"}], llm_data=llm)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def get_sensor_status(
        kind: Annotated[Literal["HYDRANT_PRESSURE", "AIR_QUALITY"] | None, Field(description="센서 종류")] = None,
        alerts_only: bool = True,
    ) -> CallToolResult:
        """IoT 센서 최신값. 소화전 수압 저하(ALERT), 버스 이동형 공기질 나쁨(BAD/VERY_BAD)을 확인한다."""
        try:
            s = await api.get("/api/v1/sensors", {"kind": kind, "alertsOnly": str(alerts_only).lower()})
        except BackendError as e:
            return error(e)
        rows = [{"code": x["code"], "kind": x["kind"], "status": x["status"], "values": x["values"],
                 "facilityId": x["facilityId"], "lon": x["lon"], "lat": x["lat"]} for x in s["readings"][:20]]
        ui: list[dict[str, Any]] = [{"type": "showLayer", "layer": "sensors", "visible": True}]
        if rows and alerts_only:
            ui.append({"type": "flyTo", "lon": rows[0]["lon"], "lat": rows[0]["lat"], "height": 300})
        summary = f"센서 {s['total']}개 중 경보 {s['alerts']}건"
        return result(summary, s, ui, llm_data=rows)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def list_3d_models() -> CallToolResult:
        """업로드된 3D 모델(glTF/GLB)과 처리 상태·메타데이터."""
        try:
            models = await api.get("/api/v1/models")
        except BackendError as e:
            return error(e)
        rows = [{"id": m["id"], "name": m["name"], "status": m["status"], "lon": m["lon"], "lat": m["lat"],
                 "triangles": (m.get("stats") or {}).get("triangles")} for m in models]
        return result(f"3D 모델 {len(rows)}개", rows, [{"type": "showLayer", "layer": "models", "visible": True}],
                      llm_data=rows)

    @mcp.tool(annotations=READ_ONLY)
    @instrumented
    async def analyze_response_time(
        region: Annotated[str | None, Field(description="행정동 이름 또는 ID. 생략하면 전체")] = None,
        target_min: Annotated[float | None, Field(ge=1, le=30, description="목표 도달 시간(분). 생략하면 7분")] = None,
    ) -> CallToolResult:
        """도로망 최단경로 기준 소방서 출동 시간을 분석한다: 목표 시간 안 도달 비율, 행정동별 90백분위,
        목표를 넘는 고위험 건물. 지도에 3·5·목표 분 도달권을 표시한다."""
        try:
            r = await api.resolve_region(region)
            res = await api.post("/api/v1/analysis/response", {
                "regionId": r["id"] if r else None, "targetMin": target_min, "top": 5})
        except BackendError as e:
            return error(e)
        s = res["summary"]
        target = res["targetMin"]
        llm = {
            "method": res["method"], "targetMin": target, "withinRate": s["withinRate"],
            "beyondTarget": s["beyondTarget"], "highRiskBeyond": s["highRiskBeyond"], "p50Min": s["p50Min"],
            "p90Min": s["p90Min"], "maxMin": s["maxMin"], "farFromRoad": s["farFromRoad"],
            "regions": [{"name": x["regionName"], "withinRate": x["withinRate"], "p90Min": x["p90Min"]}
                        for x in res["regions"]],
            "slowest": [{"id": b["id"], "name": b["name"], "risk": b["risk"], "responseMin": b["responseMin"],
                         "station": b["stationName"]} for b in res["slowest"]],
        }
        minutes = sorted({3.0, 5.0, float(target)})
        ui = [
            {"type": "showIsochrones", "minutes": minutes},
            {"type": "highlightBuildings", "ids": res["beyondTargetIds"], "label": f"출동 {target:g}분 초과 건물"},
        ]
        summary = (f"출동 {target:g}분 이내 {s['withinRate']}% ({r['name'] if r else '전체'}, 초과 {s['beyondTarget']}동, "
                   f"초과 고위험 {s['highRiskBeyond']}동, 90백분위 {s['p90Min']}분)")
        return result(summary, res, ui, llm_data=llm)

    @mcp.custom_route("/healthz", methods=["GET"], include_in_schema=False)
    async def healthz(request: Request) -> Response:
        return PlainTextResponse("ok")

    @mcp.custom_route("/metrics", methods=["GET"], include_in_schema=False)
    async def metrics(request: Request) -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    # ------------------------------------------------------------------ resources
    @mcp.resource("twin://schema", name="schema", mime_type="text/markdown")
    def schema() -> str:
        """데이터 사전."""
        return SCHEMA_DOC

    @mcp.resource("twin://standards", name="standards", mime_type="text/markdown")
    def standards() -> str:
        """분석 기준값과 근거."""
        return STANDARDS_DOC

    # ------------------------------------------------------------------ prompts
    @mcp.prompt(name="safety_briefing", description="행정동 화재·응급 안전 브리핑 작성")
    def safety_briefing(region: str) -> str:
        return (f"{region}의 화재·응급 안전 브리핑을 작성해줘. analyze_coverage(HYDRANT, AED)와 "
                f"search_buildings(uncovered_by=HYDRANT, min_age=30)로 근거를 확인하고, 필요하면 "
                f"recommend_facility_sites로 개선안을 제시해. 수치는 도구 결과만 인용하고 {DISCLAIMER}")

    mcp._twinpilot_api = api   # stdio 실행 시 토큰 공급자를 바꾸기 위해 보관
    return mcp


SCHEMA_DOC = """# TwinPilot 데이터 사전
- region: 행정동(id, code, name, zone_type[RESIDENTIAL|COMMERCIAL|INDUSTRIAL|GREEN|OTHER])
- building: 건축물대장 표제부 대응(use_type, structure[RC|STEEL|MASONRY|WOOD], floors, basement_floors,
  height_m, built_year, risk_score 0~100, risk_factors{age,structure,height,hydrant,response,...})
- facility: HYDRANT(지상식/지하식/급수탑) · AED · FIRE_STATION, status[ACTIVE|INACTIVE|PLANNED]
- sensor: HYDRANT_PRESSURE(MPa) · AIR_QUALITY(pm25, pm10, vocPpb; 버스 이동형)
- 좌표계: 저장 EPSG:4326, 거리·면적 계산 EPSG:5186
""" + DISCLAIMER

STANDARDS_DOC = """# 분석 기준
| 항목 | 값 | 근거 |
|---|---|---|
| 소화전 반경 | 주거·상업·공업지역 100m, 그 외 140m | 소방기본법 시행규칙 [별표 3] 수평거리 |
| AED 반경 | 300m | 가정값(보행 약 4분) |
| 소방서 도달 목표 | 7분 | 화재 골든타임 |
| 출동 시간 | 도로망 최단경로(폭 20m 이상 40km/h, 12m 이상 30km/h, 그 외 20km/h) + 출동 준비 60초 | 가정값(일방통행·신호·혼잡 미반영) |
| 출동 시간(도로망 없을 때) | 직선거리 × 1.3 ÷ 30km/h | 근사 |
| 사다리차 유효 높이 | 45m | 가정값 |
| 고위험 | 위험도 60점 이상 | 서비스 기준 |
위험도 = 노후도 30 + 구조 20 + 층수 15 + 최근접 소화전 거리 20 + 소방서 출동 시간 15
"""
