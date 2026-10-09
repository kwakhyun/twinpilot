"""실행: python -m twinpilot_mcp [--transport streamable-http|stdio]

- streamable-http: 에이전트·원격 클라이언트용(기본, http://HOST:PORT/mcp). OIDC 보호 리소스로 동작한다.
- stdio: Claude Desktop 등 로컬 MCP 클라이언트용. twin-api 호출에는 서비스 계정 토큰을 쓴다.
"""
import argparse
import logging
import os

from .auth import AuthConfig
from .server import create_server
from .telemetry import setup_logging, setup_tracing

log = logging.getLogger("twinpilot_mcp")


def main() -> None:
    parser = argparse.ArgumentParser(description="TwinPilot MCP server")
    parser.add_argument("--transport", choices=["streamable-http", "stdio"],
                        default=os.getenv("MCP_TRANSPORT", "streamable-http"))
    parser.add_argument("--host", default=os.getenv("MCP_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("MCP_PORT", "8090")))
    args = parser.parse_args()

    setup_logging("mcp-server")
    tracing = setup_tracing("mcp-server")
    auth = AuthConfig.from_env()
    if auth is None:
        log.warning("MCP_AUTH_MODE=disabled: 인증 없이 동작합니다. 로컬 개발에서만 쓰세요.")

    if args.transport == "stdio":
        # stdio에는 들어오는 토큰이 없으므로 검증기 없이 서비스 계정으로 twin-api를 호출한다.
        server = create_server(host=args.host, port=args.port, auth=None)
        if auth is not None and auth.client_secret:
            from .auth import UpstreamTokens
            upstream = UpstreamTokens(auth)
            server_api = server._twinpilot_api   # noqa: SLF001 — create_server가 붙여 둔 클라이언트
            server_api.token_provider = upstream.service
            server_api.on_unauthorized = lambda: upstream.invalidate(None)
        server.run(transport="stdio")
        return

    import uvicorn

    server = create_server(host=args.host, port=args.port, auth=auth)
    app = server.streamable_http_app()
    if tracing:
        from opentelemetry.instrumentation.asgi import OpenTelemetryMiddleware
        app = OpenTelemetryMiddleware(app, excluded_urls="healthz,metrics")
    uvicorn.run(app, host=args.host, port=args.port, log_config=None, access_log=False, proxy_headers=True,
                forwarded_allow_ips=os.getenv("FORWARDED_ALLOW_IPS", "127.0.0.1"),
                timeout_graceful_shutdown=20)


if __name__ == "__main__":
    main()
