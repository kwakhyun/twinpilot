# E2E (Playwright)

전체 스택을 띄운 뒤(`docker compose -f infra/docker-compose.yml up -d`) 실행한다.

```bash
cd e2e && npm install && npx playwright install chromium
BASE=http://localhost:8088 npm run ui        # 지도·커버리지·건물 상세·추천 Job·대시보드
BASE=http://localhost:8088 npm run copilot   # AI 코파일럿 3개 질의(세션 유지)
BASE=http://localhost:8088 npm run models    # glTF 업로드·배치·Three.js 미리보기
```

스크린샷은 `OUT`(기본 `./screenshots`)에 저장된다. 각 시나리오는 브라우저 콘솔 오류도 함께 출력한다.
