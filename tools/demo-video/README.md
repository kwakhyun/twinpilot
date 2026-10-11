# 시연 영상 만들기

실제 스택을 브라우저(Playwright)로 조작하며 녹화하고, 인트로·아웃트로 모션 그래픽과 자막을 합성한다.

```bash
docker compose -f infra/docker-compose.yml up -d --wait          # 스택 실행
cd e2e && npm ci                                                  # Playwright
mkdir -p ../tools/demo-video/take && VID=$PWD/../tools/demo-video/take node ../tools/demo-video/record.mjs
python3 ../tools/demo-video/assemble.py ../tools/demo-video/take demo.mp4 22
```

- `record.mjs`: CDP screencast로 프레임과 시각을 저장한다. 헤드리스에는 커서가 없어 커서·클릭 효과를 화면에 그린다.
  `wait()`로 표시한 구간은 "보여 주는 구간", 나머지는 조작·로딩 구간이다.
- `assemble.py`: 보여 주는 구간은 실제 속도, 조작·로딩 구간은 `SPEED`배(기본 2.5) 빠르게 이어 붙이고
  자막(ASS, Pretendard 글꼴)을 입혀 1920×1080 30fps H.264로 만든다. GPU 없는 환경에서 3D 렌더링이 느린 것을 보정한다.
- 글꼴: Pretendard(`npm pack pretendard` 후 `~/.fonts`에 설치), ffmpeg(libass 포함) 필요.
