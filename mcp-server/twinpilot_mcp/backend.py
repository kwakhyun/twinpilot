"""twin-api(Java) REST 클라이언트. MCP 도구는 이 클라이언트로만 데이터에 접근한다(임의 SQL 없음)."""
from __future__ import annotations

import asyncio
import os
from typing import Any

import httpx


class BackendError(RuntimeError):
    pass


class TwinApi:
    def __init__(self, base_url: str | None = None, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = (base_url or os.getenv("TWIN_API_URL", "http://localhost:8080")).rstrip("/")
        self._client = httpx.AsyncClient(base_url=self.base_url, timeout=30.0, transport=transport)
        self._regions: list[dict[str, Any]] | None = None

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            res = await self._client.request(method, path, **kwargs)
        except httpx.HTTPError as e:
            raise BackendError(f"twin-api 연결 실패: {e}") from e
        if res.status_code >= 400:
            try:
                detail = res.json().get("detail") or res.text
            except ValueError:
                detail = res.text
            raise BackendError(f"twin-api {res.status_code}: {detail}")
        return res.json() if res.content else None

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        return await self._request("GET", path, params=clean)

    async def post(self, path: str, body: dict[str, Any]) -> Any:
        return await self._request("POST", path, json={k: v for k, v in body.items() if v is not None})

    async def regions(self) -> list[dict[str, Any]]:
        """행정동 목록(캐시). 이름→ID 해석에 쓴다."""
        if self._regions is None:
            fc = await self.get("/api/v1/regions")
            self._regions = [{"id": f["id"], **f["properties"]} for f in fc["features"]]
        return self._regions

    async def resolve_region(self, region: str | int | None) -> dict[str, Any] | None:
        """'다솜동', '다솜', '3' 같은 입력을 행정동으로 해석한다. 없으면 None, 모호하면 오류."""
        if region is None or (isinstance(region, str) and not region.strip()):
            return None
        regions = await self.regions()
        if isinstance(region, int) or (isinstance(region, str) and region.strip().isdigit()):
            rid = int(region)
            match = [r for r in regions if r["id"] == rid]
        else:
            key = region.strip().removesuffix("동")
            match = [r for r in regions if r["name"].removesuffix("동") == key or key in r["name"]]
        if len(match) != 1:
            names = ", ".join(r["name"] for r in regions)
            raise BackendError(f"행정동 '{region}'을(를) 찾을 수 없습니다. 가능한 값: {names}")
        return match[0]

    async def wait_job(self, job_id: str, on_progress=None, timeout_s: float = 90.0) -> dict[str, Any]:
        """비동기 Job 완료를 기다린다(폴링). 진행률은 콜백으로 전달한다."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_s
        last = -1
        while True:
            job = await self.get(f"/api/v1/analysis/jobs/{job_id}")
            if on_progress and job["progress"] != last:
                last = job["progress"]
                await on_progress(job["progress"], job.get("message") or "")
            if job["status"] in ("SUCCEEDED", "FAILED"):
                return job
            if loop.time() > deadline:
                raise BackendError(f"Job {job_id} 시간 초과")
            await asyncio.sleep(0.3)
