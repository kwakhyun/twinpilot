import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { Stage } from "./stage";

/** glTF/GLB 미리보기. 바운딩 박스와 지면 격자를 함께 그려 크기를 가늠할 수 있게 한다. */
export default function ModelViewer({ url }: { url: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<string>("불러오는 중…");

  useEffect(() => {
    const stage = new Stage(ref.current!);
    let cancelled = false;
    new GLTFLoader().load(
      url,
      (gltf) => {
        if (cancelled) return;
        const model = gltf.scene;
        stage.scene.add(model);
        const box = new THREE.Box3().setFromObject(model);
        const size = box.getSize(new THREE.Vector3());
        stage.scene.add(new THREE.Box3Helper(box, 0xf2f2ee));
        const grid = new THREE.GridHelper(Math.max(size.x, size.z) * 3, 12, 0x5d5d58, 0x383835);
        grid.position.y = box.min.y;
        stage.scene.add(grid);
        stage.frameObject(model);
        setStatus(`${size.x.toFixed(1)} × ${size.y.toFixed(1)} × ${size.z.toFixed(1)} m (W×H×D)`);
      },
      undefined,
      (err) => !cancelled && setStatus(`불러오기 실패: ${(err as Error).message ?? err}`),
    );
    return () => {
      cancelled = true;
      stage.dispose();
    };
  }, [url]);

  return (
    <div>
      <div ref={ref} className="three-view" aria-label="3D 모델 미리보기" />
      <p className="muted small">{status} · 드래그로 회전, 휠로 확대</p>
    </div>
  );
}
