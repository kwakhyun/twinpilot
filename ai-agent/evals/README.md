# 코파일럿 평가(evals)

실행 중인 스택의 `/agent/chat`에 [cases.yaml](cases.yaml)의 질문을 보내 채점한다. 브라우저와 같은 경로
(nginx → ai-agent → MCP → twin-api)와 같은 인증(Keycloak PKCE 로그인, 역할별 계정)을 쓴다.

| 항목 | 기준 |
|---|---|
| 도구 선택 | 기대 도구 호출, 인자 부분 일치(`tools`, `tool_args`), 불필요한 호출 없음(`max_tool_calls`) |
| 근거성 | 답변의 수치가 twin-api가 직접 계산한 실제값과 같다(`truth`) |
| 지도 연동 | 기대한 지도 액션(`ui_actions`)이 나온다 |
| 안전성 | 범위 밖 질문에 도구를 쓰지 않음, 예산 등 추측 금지, 시스템 프롬프트 비공개, 권한 밖 요청은 거절 안내 |
| 운영 | 응답 시간 상한, LLM 장애 시 규칙 기반 전환(fallback) 횟수 |

```bash
docker compose -f infra/docker-compose.yml up -d --wait          # ANTHROPIC_API_KEY가 있으면 LLM 모드
pip install httpx pyyaml                                          # 또는 ai-agent 가상환경
python ai-agent/evals/run_eval.py --base http://localhost:8088 --password twinpilot-demo --out eval-report
```

- 에이전트 모드는 서버가 정한다. 응답의 `done` 이벤트로 모드를 확인하고 `modes`가 맞지 않는 사례는 건너뛴다.
  규칙 기반 모드에서는 대화 맥락·추측 금지·프롬프트 주입 사례(LLM 전용 3건)를 건너뛴다.
- 사용자별 요청 한도(분당 20회)에 맞춰 질문 사이에 3.5초 간격을 둔다(`--delay`). 429를 받으면 `Retry-After`만큼 기다린다.
- 결과는 `eval-report.json`(사례별 이벤트 요약·실패 사유)과 `eval-report.md`(요약 표)로 남는다.
  통과율이 `--min-pass`(기본 0.8)보다 낮으면 종료 코드 1이므로 배포 전 점검에 쓸 수 있다.
- 숫자 기준값(`truth`)은 실행 시점에 twin-api에서 받아 오므로 데이터가 바뀌어도 그대로 쓸 수 있다.

채점 로직 자체는 `tests/test_eval_harness.py`가 네트워크 없이 검증한다.
측정 결과는 [docs/05-verification.md](../../docs/05-verification.md#코파일럿-평가)에 정리했다.
