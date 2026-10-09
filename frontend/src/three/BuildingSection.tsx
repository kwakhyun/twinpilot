import { useEffect, useRef } from "react";
import * as THREE from "three";
import type { GeoJSONGeometry } from "../api/types";
import { polygonsOf } from "../map/geo";
import { Stage } from "./stage";

interface Props {
  footprint: GeoJSONGeometry;
  floors: number;
  basementFloors: number;
  floorHeightM: number;
  ladderReachM: number;
}

const COLORS = {
  reachable: 0x6da7ec,
  beyond: 0xd03b3b,
  basement: 0x4b4b47,
  ground: 0x2c2c2a,
  plane: 0xf2f2ee,
};

/** 외곽선 경위도 → 건물 중심 기준 로컬 미터 좌표(x: 동, z: 남). */
export function footprintToLocal(footprint: GeoJSONGeometry): THREE.Vector2[] {
  const ring = polygonsOf(footprint)[0]?.[0] ?? [];
  const pts = ring.slice(0, -1);
  if (pts.length < 3) return [];
  const lon0 = pts.reduce((s, p) => s + p[0], 0) / pts.length;
  const lat0 = pts.reduce((s, p) => s + p[1], 0) / pts.length;
  const mx = 111_320 * Math.cos((lat0 * Math.PI) / 180);
  return pts.map(([lon, lat]) => new THREE.Vector2((lon - lon0) * mx, (lat - lat0) * 110_540));
}

/**
 * 층별 단면 뷰: 실제 외곽선을 층 단위 슬래브로 쌓고, 사다리차 유효 높이를 넘는 층을 강조한다.
 */
export default function BuildingSection({ footprint, floors, basementFloors, floorHeightM, ladderReachM }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const stage = new Stage(ref.current!);
    const outline = footprintToLocal(footprint);
    const shape = new THREE.Shape(outline.length ? outline : [new THREE.Vector2(-8, -8), new THREE.Vector2(8, -8), new THREE.Vector2(8, 8), new THREE.Vector2(-8, 8)]);
    const slab = Math.max(floorHeightM - 0.5, 0.6);
    const group = new THREE.Group();
    const mats = {
      reachable: new THREE.MeshStandardMaterial({ color: COLORS.reachable, roughness: 0.7 }),
      beyond: new THREE.MeshStandardMaterial({ color: COLORS.beyond, roughness: 0.6 }),
      basement: new THREE.MeshStandardMaterial({ color: COLORS.basement, roughness: 0.9 }),
    };
    const geometry = new THREE.ExtrudeGeometry(shape, { depth: slab, bevelEnabled: false });
    geometry.rotateX(-Math.PI / 2); // Shape(x,y) → 지면(x,z), 높이는 +y

    for (let i = -basementFloors; i < floors; i++) {
      const top = (i + 1) * floorHeightM;
      const mat = i < 0 ? mats.basement : top > ladderReachM ? mats.beyond : mats.reachable;
      const mesh = new THREE.Mesh(geometry, mat);
      mesh.position.y = i * floorHeightM;
      group.add(mesh);
    }
    stage.scene.add(group);

    const extent = Math.max(40, ...outline.map((p) => p.length() * 2.4));
    const ground = new THREE.Mesh(
      new THREE.PlaneGeometry(extent, extent),
      new THREE.MeshStandardMaterial({ color: COLORS.ground, transparent: true, opacity: 0.85 }),
    );
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.05;
    stage.scene.add(ground);

    const reachPlane = new THREE.Mesh(
      new THREE.PlaneGeometry(extent * 0.8, extent * 0.8),
      new THREE.MeshBasicMaterial({ color: COLORS.plane, transparent: true, opacity: 0.12, side: THREE.DoubleSide }),
    );
    reachPlane.rotation.x = -Math.PI / 2;
    reachPlane.position.y = ladderReachM;
    if (floors * floorHeightM > ladderReachM) stage.scene.add(reachPlane);

    stage.frameObject(group);
    return () => stage.dispose();
  }, [footprint, floors, basementFloors, floorHeightM, ladderReachM]);

  return <div ref={ref} className="three-view" role="img" aria-label="건물 층별 단면 3D 뷰" />;
}
