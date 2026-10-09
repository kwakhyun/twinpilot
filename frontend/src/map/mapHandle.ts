import type { TwinScene } from "./TwinScene";

// 지도 밖 패널(모델 배치 등)이 현재 장면에 질의할 때 쓰는 핸들.
let current: TwinScene | null = null;

export const mapHandle = {
  set(scene: TwinScene | null) {
    current = scene;
  },
  get(): TwinScene | null {
    return current;
  },
};
