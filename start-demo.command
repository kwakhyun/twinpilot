#!/bin/bash
# macOS: Finder에서 더블클릭하면 터미널에서 start-demo.sh를 실행한다.
cd "$(dirname "$0")" && ./start-demo.sh "$@"
echo; read -r -p "Enter를 누르면 닫힙니다"
