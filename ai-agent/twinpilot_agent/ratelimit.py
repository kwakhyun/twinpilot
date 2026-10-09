"""사용자별 토큰 버킷. LLM 비용과 백엔드 부하를 사용자 단위로 제한한다(레플리카마다 따로 센다)."""
from __future__ import annotations

import time
from dataclasses import dataclass


@dataclass
class _Bucket:
    tokens: float
    updated: float


class RateLimiter:
    def __init__(self, per_minute: float, burst: int):
        self.rate = per_minute / 60.0
        self.burst = burst
        self._buckets: dict[str, _Bucket] = {}

    def allow(self, key: str) -> tuple[bool, float]:
        """허용 여부와, 거부 시 다시 시도할 수 있을 때까지의 초."""
        now = time.monotonic()
        b = self._buckets.get(key)
        if b is None:
            b = self._buckets[key] = _Bucket(tokens=float(self.burst), updated=now)
        b.tokens = min(self.burst, b.tokens + (now - b.updated) * self.rate)
        b.updated = now
        if len(self._buckets) > 10000:   # 오래 쓰지 않은 사용자 정리
            self._buckets = {k: v for k, v in self._buckets.items() if now - v.updated < 600}
        if b.tokens >= 1:
            b.tokens -= 1
            return True, 0.0
        return False, (1 - b.tokens) / self.rate if self.rate > 0 else 60.0
