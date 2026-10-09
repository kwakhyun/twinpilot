"""실행: python -m twinpilot_mcp [--transport streamable-http|stdio]

- streamable-http: 에이전트·원격 클라이언트용(기본, http://HOST:PORT/mcp)
- stdio: Claude Desktop 등 로컬 MCP 클라이언트용
"""
import argparse
import os

from .server import create_server


def main() -> None:
    parser = argparse.ArgumentParser(description="TwinPilot MCP server")
    parser.add_argument("--transport", choices=["streamable-http", "stdio"],
                        default=os.getenv("MCP_TRANSPORT", "streamable-http"))
    parser.add_argument("--host", default=os.getenv("MCP_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("MCP_PORT", "8090")))
    args = parser.parse_args()
    create_server(host=args.host, port=args.port).run(transport=args.transport)


if __name__ == "__main__":
    main()
