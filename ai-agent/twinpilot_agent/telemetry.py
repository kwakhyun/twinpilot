"""관측성: JSON 로그(trace id 포함), Prometheus 메트릭, OpenTelemetry 추적(OTLP 엔드포인트가 있을 때)."""
from __future__ import annotations

import json
import logging
import os
import sys
import time

from prometheus_client import Counter, Histogram

CHAT_REQUESTS = Counter("twinpilot_agent_chat_total", "대화 요청 수", ["mode", "outcome"])
CHAT_LATENCY = Histogram("twinpilot_agent_chat_seconds", "대화 한 턴 처리 시간", ["mode"],
                         buckets=(0.5, 1, 2, 5, 10, 20, 30, 60, 120))
TOOL_CALLS = Counter("twinpilot_agent_tool_calls_total", "에이전트가 호출한 도구 수", ["tool", "ok"])
LLM_TOKENS = Counter("twinpilot_agent_llm_tokens_total", "LLM 토큰 사용량", ["model", "kind"])
LLM_FALLBACKS = Counter("twinpilot_agent_llm_fallback_total", "LLM 오류로 규칙 기반 응답으로 전환한 횟수", ["reason"])
RATE_LIMITED = Counter("twinpilot_agent_rate_limited_total", "사용자별 요청 한도 초과")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}Z",
                 "level": record.levelname, "logger": record.name, "message": record.getMessage()}
        try:
            from opentelemetry import trace
            ctx = trace.get_current_span().get_span_context()
            if ctx.is_valid:
                entry["trace_id"] = format(ctx.trace_id, "032x")
                entry["span_id"] = format(ctx.span_id, "016x")
        except ImportError:
            pass
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


def setup_logging(service: str) -> None:
    handler = logging.StreamHandler(sys.stderr)
    if os.getenv("LOG_FORMAT", "text").lower() == "json":
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(logging.Formatter(f"%(asctime)s %(levelname)s [{service}] %(name)s: %(message)s"))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())
    for noisy in ("httpx", "httpcore", "mcp.client"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def setup_tracing(service: str, app=None) -> bool:
    if not os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") and not os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"):
        return False
    from opentelemetry import trace
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    provider = TracerProvider(resource=Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", service)}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    HTTPXClientInstrumentor().instrument()
    if app is not None:
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        FastAPIInstrumentor.instrument_app(app, excluded_urls="agent/health,metrics")
    return True
