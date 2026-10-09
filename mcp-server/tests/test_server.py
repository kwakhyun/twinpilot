"""MCP 서버 테스트: 메모리 내 MCP 세션 + 모의 twin-api(httpx.MockTransport)."""
import json
import os

import httpx
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from twinpilot_mcp.backend import TwinApi
from twinpilot_mcp.server import create_server

REGIONS = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature", "id": 3, "geometry": None,
         "properties": {"code": "26230-103", "name": "다솜동", "zoneType": "RESIDENTIAL", "hydrantRadiusM": 100,
                        "areaM2": 1, "buildingCount": 1486}},
        {"type": "Feature", "id": 4, "geometry": None,
         "properties": {"code": "26230-104", "name": "한빛동", "zoneType": "INDUSTRIAL", "hydrantRadiusM": 100,
                        "areaM2": 1, "buildingCount": 216}},
    ],
}


def building(i: int, risk: float) -> dict:
    return {"id": i, "name": f"다솜동 {i:04d}", "useType": "RESIDENTIAL_HOUSE", "structure": "MASONRY", "floors": 2,
            "heightM": 7, "builtYear": 1970, "age": 56, "regionId": 3, "regionName": "다솜동", "risk": risk,
            "riskFactors": {"nearestHydrantM": 150.0}, "lon": 129.05, "lat": 35.15}


class FakeBackend:
    def __init__(self):
        self.requests: list[httpx.Request] = []
        self.job_polls = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path == "/api/v1/regions":
            return httpx.Response(200, json=REGIONS)
        if path == "/api/v1/buildings/search":
            items = [building(i, 80 - i) for i in range(1, 31)]
            return httpx.Response(200, json={"total": 30, "avgRisk": 65.5, "maxRisk": 79, "returned": 30,
                                             "sort": "risk", "items": items})
        if path == "/api/v1/analysis/coverage":
            return httpx.Response(200, json={
                "facilityType": "HYDRANT", "includePlanned": False, "radiusOverrideM": None,
                "summary": {"totalBuildings": 1486, "coveredBuildings": 1333, "coverageRate": 89.7,
                            "uncoveredHighRisk": 64, "blindSpotAreaM2": 0},
                "regions": [{"regionId": 3, "regionName": "다솜동", "zoneType": "RESIDENTIAL", "radiusM": 100,
                             "totalBuildings": 1486, "coveredBuildings": 1333, "coverageRate": 89.7,
                             "uncoveredHighRisk": 64, "uncoveredRiskSum": 1}],
                "uncoveredBuildingIds": list(range(1, 154)),
                "topUncovered": [{"id": 1, "name": "다솜동 0001", "useType": "RESIDENTIAL_HOUSE", "builtYear": 1966,
                                  "floors": 2, "risk": 73.9, "regionId": 3, "nearestFacilityM": 188.7,
                                  "lon": 129.05, "lat": 35.15}],
                "blindSpots": [], "standard": "소방기본법 시행규칙 [별표 3]", "elapsedMs": 100})
        if path == "/api/v1/analysis/jobs":
            return httpx.Response(202, json={"id": "job-1", "status": "QUEUED", "progress": 0, "message": "대기"})
        if path == "/api/v1/analysis/jobs/job-1":
            self.job_polls += 1
            if self.job_polls < 2:
                return httpx.Response(200, json={"id": "job-1", "status": "RUNNING", "progress": 50, "message": "계산"})
            site = {"rank": 1, "lon": 129.06, "lat": 35.15, "regionId": 4, "regionName": "한빛동", "radiusM": 300,
                    "newlyCoveredBuildings": 100, "riskCovered": 3746, "buildingIds": [5, 6, 7]}
            return httpx.Response(200, json={"id": "job-1", "status": "SUCCEEDED", "progress": 100, "message": "완료",
                                             "result": {"facilityType": "AED", "requested": 1, "sites": [site],
                                                        "candidateCount": 246, "totalBuildings": 216,
                                                        "coverageBefore": 44.4, "coverageAfter": 90.7,
                                                        "newlyCovered": 100, "riskCovered": 3746,
                                                        "method": "MCLP", "elapsedMs": 1000}})
        return httpx.Response(404, json={"detail": f"no route {path}"})


@pytest.fixture
def backend():
    return FakeBackend()


@pytest.fixture
def server(backend):
    return create_server(TwinApi("http://twin-api", transport=httpx.MockTransport(backend)))


async def test_lists_tools_resources_and_prompts(server):
    async with create_connected_server_and_client_session(server) as client:
        tools = {t.name: t for t in (await client.list_tools()).tools}
        assert set(tools) == {"list_regions", "search_buildings", "get_building_detail", "analyze_coverage",
                              "recommend_facility_sites", "get_dashboard_stats", "get_sensor_status", "list_3d_models"}
        assert all(t.annotations.readOnlyHint for t in tools.values())
        # Context 파라미터는 도구 입력 스키마에 노출되면 안 된다.
        assert "ctx" not in tools["recommend_facility_sites"].inputSchema["properties"]
        assert tools["analyze_coverage"].inputSchema["properties"]["facility_type"]["enum"] == ["HYDRANT", "AED", "FIRE_STATION"]

        resources = {str(r.uri) for r in (await client.list_resources()).resources}
        assert resources == {"twin://schema", "twin://standards"}
        standards = await client.read_resource("twin://standards")
        assert "별표 3" in standards.contents[0].text

        prompt = await client.get_prompt("safety_briefing", {"region": "다솜동"})
        assert "다솜동" in prompt.messages[0].content.text


async def test_search_resolves_region_name_and_splits_llm_text_from_ui_actions(server, backend):
    async with create_connected_server_and_client_session(server) as client:
        res = await client.call_tool("search_buildings", {"region": "다솜", "min_age": 30, "uncovered_by": "HYDRANT", "limit": 5})
    assert not res.isError
    text = res.content[0].text
    assert text.startswith("조건(다솜동, 30년 이상, 소화전 사각지대)")
    # LLM 텍스트에는 상위 5개만, 지도 하이라이트에는 전체 30개 ID
    assert len(json.loads(text.split("\n", 1)[1])) == 5
    actions = res.structuredContent["ui_actions"]
    assert actions[0]["type"] == "highlightBuildings" and len(actions[0]["ids"]) == 30
    search = [r for r in backend.requests if r.url.path == "/api/v1/buildings/search"][0]
    assert search.url.params["regionId"] == "3" and search.url.params["uncoveredBy"] == "HYDRANT"


async def test_unknown_region_is_a_tool_error_not_a_crash(server):
    async with create_connected_server_and_client_session(server) as client:
        res = await client.call_tool("analyze_coverage", {"facility_type": "HYDRANT", "region": "없는동"})
    assert res.isError
    assert "다솜동, 한빛동" in res.content[0].text


async def test_invalid_arguments_are_rejected_by_schema(server):
    async with create_connected_server_and_client_session(server) as client:
        res = await client.call_tool("analyze_coverage", {"facility_type": "DROP TABLE"})
    assert res.isError


async def test_coverage_emits_map_actions(server):
    async with create_connected_server_and_client_session(server) as client:
        res = await client.call_tool("analyze_coverage", {"facility_type": "HYDRANT", "region": "다솜동"})
    assert "89.7%" in res.content[0].text
    types = [a["type"] for a in res.structuredContent["ui_actions"]]
    assert types == ["runCoverage", "highlightBuildings"]
    assert len(res.structuredContent["ui_actions"][1]["ids"]) == 153


async def test_recommendation_waits_for_async_job_and_reports_progress(server):
    progress: list[float] = []

    async def on_progress(p, total, message):
        progress.append(p)

    async with create_connected_server_and_client_session(server) as client:
        res = await client.call_tool("recommend_facility_sites", {"facility_type": "AED", "count": 1, "region": "한빛동"},
                                     progress_callback=on_progress)
    assert not res.isError
    assert "44.4% → 90.7%" in res.content[0].text
    assert progress and progress[-1] == 100
    show = res.structuredContent["ui_actions"][0]
    assert show["type"] == "showSites" and show["result"]["sites"][0]["radiusM"] == 300


@pytest.mark.skipif(not os.getenv("TWIN_API_URL"), reason="실행 중인 twin-api가 필요(TWIN_API_URL)")
async def test_against_live_backend():
    server = create_server(TwinApi(os.environ["TWIN_API_URL"]))
    async with create_connected_server_and_client_session(server) as client:
        regions = await client.call_tool("list_regions", {})
        coverage = await client.call_tool("analyze_coverage", {"facility_type": "HYDRANT", "region": "다솜동"})
        detail = await client.call_tool("get_building_detail", {"building_id": 1})
        sensors = await client.call_tool("get_sensor_status", {"alerts_only": False})
    assert not regions.isError and "행정동 4곳" in regions.content[0].text
    assert not coverage.isError and coverage.structuredContent["data"]["summary"]["totalBuildings"] > 1000
    assert not detail.isError and detail.structuredContent["ui_actions"][0] == {"type": "selectBuilding", "id": 1}
    assert not sensors.isError and sensors.structuredContent["data"]["total"] > 100
