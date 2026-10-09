#!/usr/bin/env python3
"""TwinPilot 합성 도시 데이터 생성기.

실제 건물·시설과 무관한 가상 도시를 부산진구 일대 좌표에 생성한다.
- 행정동 4개(상업·주거·주거·공업), 도로 격자, 건물 약 2천 동
- 소화전(일부 구역은 의도적으로 공백), AED, 소방서 2곳, 센서, 버스 노선

표준 라이브러리만 사용하며 seed 고정으로 항상 같은 결과를 만든다.

출력
- backend/src/main/resources/db/seed/seed.sql : 백엔드 데모 시드
- data/generated/*.geojson                    : 외부 도구용 GeoJSON
- data/sample-models/landmark-tower.glb       : 3D 모델 업로드 샘플
"""
from __future__ import annotations

import argparse
import json
import math
import random
import struct
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CENTER_LON = 129.0560
CENTER_LAT = 35.1580
ROTATION_DEG = 12.0  # 도시 격자 회전(자연스러운 배치)
WIDTH_M = 1800.0
HEIGHT_M = 1400.0
BLOCK_M = 150.0
MAIN_ROAD_W = 24.0
LOCAL_ROAD_W = 12.0

M_PER_DEG_LAT = 110_540.0
M_PER_DEG_LON = 111_320.0 * math.cos(math.radians(CENTER_LAT))


def to_lonlat(x: float, y: float) -> tuple[float, float]:
    """로컬 미터 좌표(도시 중심 기준, 회전 전)를 경위도로 변환."""
    t = math.radians(ROTATION_DEG)
    rx = x * math.cos(t) - y * math.sin(t)
    ry = x * math.sin(t) + y * math.cos(t)
    return (round(CENTER_LON + rx / M_PER_DEG_LON, 7), round(CENTER_LAT + ry / M_PER_DEG_LAT, 7))


def rect(x0: float, y0: float, x1: float, y1: float) -> list[tuple[float, float]]:
    return [to_lonlat(x0, y0), to_lonlat(x1, y0), to_lonlat(x1, y1), to_lonlat(x0, y1), to_lonlat(x0, y0)]


def wkt_polygon(ring: list[tuple[float, float]]) -> str:
    return "MULTIPOLYGON(((" + ",".join(f"{lon} {lat}" for lon, lat in ring) + ")))"


def wkt_line(pts: list[tuple[float, float]]) -> str:
    return "LINESTRING(" + ",".join(f"{lon} {lat}" for lon, lat in pts) + ")"


def wkt_point(lon: float, lat: float) -> str:
    return f"POINT({lon} {lat})"


@dataclass
class Region:
    id: int
    code: str
    name: str
    zone_type: str
    x0: float
    y0: float
    x1: float
    y1: float

    def contains(self, x: float, y: float) -> bool:
        return self.x0 <= x < self.x1 and self.y0 <= y < self.y1


@dataclass
class Building:
    id: int
    name: str
    use_type: str
    structure: str
    floors: int
    basement_floors: int
    height_m: float
    built_year: int
    region_id: int
    ring: list[tuple[float, float]]
    cx: float
    cy: float


@dataclass
class Facility:
    id: int
    type: str
    subtype: str
    name: str
    status: str
    installed_year: int
    x: float
    y: float
    extra: dict = field(default_factory=dict)


HX0, HX1 = -WIDTH_M / 2, WIDTH_M / 2
HY0, HY1 = -HEIGHT_M / 2, HEIGHT_M / 2

REGIONS = [
    Region(1, "26230-101", "가람동", "COMMERCIAL", 0, 0, HX1, HY1),       # 북동: 상업·업무 중심
    Region(2, "26230-102", "누리동", "RESIDENTIAL", HX0, 0, 0, HY1),      # 북서: 아파트 단지
    Region(3, "26230-103", "다솜동", "RESIDENTIAL", HX0, HY0, 0, 0),      # 남서: 노후 저층 주거
    Region(4, "26230-104", "한빛동", "INDUSTRIAL", 0, HY0, HX1, 0),       # 남동: 준공업
]

# 소화전을 일부러 비워 사각지대를 만드는 구역(로컬 좌표)
HYDRANT_GAPS = [
    (-760, -560, -420, -240),   # 다솜동 골목 밀집지
    (420, -520, 760, -330),     # 한빛동 창고 지역
    (-300, 420, -120, 640),     # 누리동 북측
]

PARK = (-80, -80, 120, 90)  # 녹지(건물 없음)


def region_of(x: float, y: float) -> Region:
    for r in REGIONS:
        if r.contains(x, y):
            return r
    return REGIONS[0]


def in_box(x: float, y: float, box: tuple[float, float, float, float]) -> bool:
    return box[0] <= x <= box[2] and box[1] <= y <= box[3]


def road_lines() -> tuple[list[float], list[float]]:
    xs, ys = [], []
    x = HX0
    while x <= HX1 + 1e-6:
        xs.append(x)
        x += BLOCK_M
    y = HY0
    while y <= HY1 + 1e-6:
        ys.append(y)
        y += BLOCK_M
    return xs, ys


def is_main(v: float) -> bool:
    return abs(v) < 1 or abs(abs(v) - 600) < 1 or abs(abs(v) - 450) < 1


def choose_attrs(rng: random.Random, region: Region, x: float, y: float, w: float, d: float) -> dict:
    dist_center = math.hypot(x - 300, y - 300)  # 상업 중심(역세권) 가정 위치
    if region.zone_type == "COMMERCIAL":
        if dist_center < 260 and rng.random() < 0.6:
            use = rng.choice(["OFFICE", "COMMERCIAL"])
            floors = rng.randint(12, 38)
            structure = rng.choice(["RC", "STEEL", "STEEL"])
            year = rng.randint(1995, 2022)
        else:
            use = rng.choice(["COMMERCIAL", "COMMERCIAL", "OFFICE", "RESIDENTIAL_HOUSE", "PUBLIC"])
            floors = rng.randint(3, 12)
            structure = rng.choice(["RC", "RC", "STEEL", "MASONRY"])
            year = rng.randint(1978, 2020)
    elif region.name == "누리동":
        if rng.random() < 0.55:
            use = "RESIDENTIAL_APT"
            floors = rng.randint(12, 25)
            structure = "RC"
            year = rng.choice([rng.randint(1988, 1998), rng.randint(2005, 2021)])
        else:
            use = rng.choice(["RESIDENTIAL_HOUSE", "COMMERCIAL", "SCHOOL", "PUBLIC"])
            floors = rng.randint(2, 6)
            structure = rng.choice(["RC", "MASONRY"])
            year = rng.randint(1985, 2018)
    elif region.name == "다솜동":
        use = rng.choice(["RESIDENTIAL_HOUSE"] * 6 + ["COMMERCIAL", "PUBLIC"])
        floors = rng.randint(1, 4)
        structure = rng.choice(["MASONRY", "MASONRY", "WOOD", "RC"])
        year = rng.randint(1962, 1999) if rng.random() < 0.8 else rng.randint(2000, 2019)
    else:  # 한빛동 준공업
        use = rng.choice(["INDUSTRIAL", "INDUSTRIAL", "WAREHOUSE", "COMMERCIAL"])
        floors = rng.randint(1, 5)
        structure = rng.choice(["STEEL", "STEEL", "RC", "MASONRY"])
        year = rng.randint(1975, 2015)
    if use == "SCHOOL":
        floors = rng.randint(3, 5)
    height = round(floors * (3.6 if use in ("OFFICE", "COMMERCIAL", "INDUSTRIAL", "WAREHOUSE") else 3.0) + rng.uniform(0.5, 3.0), 1)
    basement = 0 if floors < 4 else rng.randint(1, 5 if floors > 15 else 2)
    return dict(use_type=use, structure=structure, floors=floors, height_m=height, built_year=year, basement_floors=basement)


def generate(seed: int = 42):
    rng = random.Random(seed)
    xs, ys = road_lines()

    roads = []
    rid = 1
    for x in xs:
        w = MAIN_ROAD_W if is_main(x) else LOCAL_ROAD_W
        roads.append(dict(id=rid, name=f"{'대로' if w > 20 else '로'} {rid}", width_m=w,
                          pts=[to_lonlat(x, HY0), to_lonlat(x, HY1)], local=[(x, HY0), (x, HY1)]))
        rid += 1
    for y in ys:
        w = MAIN_ROAD_W if is_main(y) else LOCAL_ROAD_W
        roads.append(dict(id=rid, name=f"{'대로' if w > 20 else '로'} {rid}", width_m=w,
                          pts=[to_lonlat(HX0, y), to_lonlat(HX1, y)], local=[(HX0, y), (HX1, y)]))
        rid += 1

    buildings: list[Building] = []
    bid = 1
    for i in range(len(xs) - 1):
        for j in range(len(ys) - 1):
            bx0 = xs[i] + (MAIN_ROAD_W if is_main(xs[i]) else LOCAL_ROAD_W) / 2 + 2
            bx1 = xs[i + 1] - (MAIN_ROAD_W if is_main(xs[i + 1]) else LOCAL_ROAD_W) / 2 - 2
            by0 = ys[j] + (MAIN_ROAD_W if is_main(ys[j]) else LOCAL_ROAD_W) / 2 + 2
            by1 = ys[j + 1] - (MAIN_ROAD_W if is_main(ys[j + 1]) else LOCAL_ROAD_W) / 2 - 2
            cx, cy = (bx0 + bx1) / 2, (by0 + by1) / 2
            region = region_of(cx, cy)
            if in_box(cx, cy, PARK):
                continue
            # 지역별 필지 크기: 노후 주거는 잘게, 아파트·공업은 크게
            if region.name == "다솜동":
                lot = rng.uniform(14, 20)
            elif region.name == "누리동":
                lot = rng.uniform(28, 42)
            elif region.zone_type == "INDUSTRIAL":
                lot = rng.uniform(30, 48)
            else:
                lot = rng.uniform(20, 34)
            nx = max(1, int((bx1 - bx0) // lot))
            ny = max(1, int((by1 - by0) // lot))
            lw, ld = (bx1 - bx0) / nx, (by1 - by0) / ny
            for a in range(nx):
                for b in range(ny):
                    if rng.random() < 0.08:  # 공지·주차장
                        continue
                    inset = rng.uniform(1.5, 4.0)
                    x0 = bx0 + a * lw + inset
                    y0 = by0 + b * ld + inset
                    x1 = bx0 + (a + 1) * lw - inset
                    y1 = by0 + (b + 1) * ld - inset
                    if x1 - x0 < 6 or y1 - y0 < 6:
                        continue
                    pcx, pcy = (x0 + x1) / 2, (y0 + y1) / 2
                    attrs = choose_attrs(rng, region, pcx, pcy, x1 - x0, y1 - y0)
                    name = f"{region.name} {bid:04d}"
                    if attrs["use_type"] == "RESIDENTIAL_APT":
                        name = f"{region.name} {rng.choice(['푸른', '한결', '새솔', '늘봄', '해맑은'])}아파트 {bid % 20 + 101}동"
                    elif attrs["use_type"] == "OFFICE" and attrs["floors"] >= 20:
                        name = f"{rng.choice(['가람', '서면', '중앙', '동천', '부전'])}타워 {bid:04d}"
                    elif attrs["use_type"] == "SCHOOL":
                        name = f"{region.name} {rng.choice(['초등학교', '중학교'])}"
                    buildings.append(Building(bid, name, region_id=region.id, ring=rect(x0, y0, x1, y1), cx=pcx, cy=pcy, **attrs))
                    bid += 1

    facilities: list[Facility] = []
    fid = 1
    # 소화전: 교차로와 도로 구간 중간점에 배치하고 일부 구역은 공백으로 둔다
    seen = set()
    for x in xs:
        for y in ys:
            for (px, py) in [(x, y), (x + BLOCK_M / 2, y), (x, y + BLOCK_M / 2)]:
                if not (HX0 <= px <= HX1 and HY0 <= py <= HY1):
                    continue
                key = (round(px), round(py))
                if key in seen:
                    continue
                seen.add(key)
                if any(in_box(px, py, g) for g in HYDRANT_GAPS):
                    continue
                if rng.random() < 0.22:  # 실제 설치 간격의 불균일성
                    continue
                ox, oy = rng.uniform(-6, 6), rng.uniform(-6, 6)
                sub = rng.choice(["ABOVE_GROUND"] * 6 + ["UNDERGROUND"] * 3 + ["WATER_TOWER"])
                status = "ACTIVE" if rng.random() > 0.04 else "INACTIVE"
                facilities.append(Facility(fid, "HYDRANT", sub, f"소화전-{fid:04d}", status, rng.randint(1990, 2024), px + ox, py + oy))
                fid += 1
    # AED: 공공·학교·대형 아파트·대형 상업시설 일부
    for b in buildings:
        p = {"PUBLIC": 0.5, "SCHOOL": 1.0, "RESIDENTIAL_APT": 0.12, "OFFICE": 0.12, "COMMERCIAL": 0.015}.get(b.use_type, 0.0)
        if rng.random() < p:
            facilities.append(Facility(fid, "AED", "INDOOR", f"AED-{fid:04d} ({b.name})", "ACTIVE", rng.randint(2012, 2025), b.cx, b.cy, {"building_id": b.id}))
            fid += 1
    # 소방서: 1곳은 영역 북서쪽, 1곳은 영역 밖 동쪽
    facilities.append(Facility(fid, "FIRE_STATION", "STATION", "가람소방서", "ACTIVE", 1998, -820, 640)); fid += 1
    facilities.append(Facility(fid, "FIRE_STATION", "SAFETY_CENTER", "동천119안전센터", "ACTIVE", 2006, 1350, -150)); fid += 1

    # 센서: 소화전 수압(활성 소화전의 약 40%), 버스 이동형 공기질(노선 2개, 센서 4대)
    sensors = []
    sid = 1
    for f in facilities:
        if f.type == "HYDRANT" and f.status == "ACTIVE" and rng.random() < 0.4:
            sensors.append(dict(id=sid, code=f"HP-{sid:04d}", kind="HYDRANT_PRESSURE", facility_id=f.id, route_id=None,
                                base=round(rng.uniform(0.25, 0.45), 3)))
            sid += 1
    bus_routes = [
        dict(id=1, name="순환 11번", local=[(-750, 600), (750, 600), (750, -600), (-750, -600), (-750, 600)]),
        dict(id=2, name="간선 52번", local=[(-900, 0), (0, 0), (0, -450), (900, -450)]),
    ]
    for r in bus_routes:
        for k in range(2):
            sensors.append(dict(id=sid, code=f"AQ-{sid:04d}", kind="AIR_QUALITY", facility_id=None, route_id=r["id"],
                                base=round(rng.uniform(15, 30), 1), offset=k * 0.5))
            sid += 1

    return roads, buildings, facilities, sensors, bus_routes


def sql_str(s: str | None) -> str:
    if s is None:
        return "NULL"
    return "'" + s.replace("'", "''") + "'"


def write_sql(path: Path, roads, buildings, facilities, sensors, bus_routes):
    lines = [
        "-- TwinPilot 합성(가상) 데모 데이터. 실제 건물·시설과 무관하다.",
        "-- generated by data/generate_synthetic_city.py (seed 고정)",
        "BEGIN;",
    ]
    lines.append("INSERT INTO region (id, code, name, zone_type, geom) VALUES")
    lines.append(",\n".join(
        f"({r.id}, {sql_str(r.code)}, {sql_str(r.name)}, {sql_str(r.zone_type)}, ST_GeomFromText('{wkt_polygon(rect(r.x0, r.y0, r.x1, r.y1))}', 4326))"
        for r in REGIONS) + ";")
    lines.append("INSERT INTO road (id, name, width_m, geom) VALUES")
    lines.append(",\n".join(
        f"({r['id']}, {sql_str(r['name'])}, {r['width_m']}, ST_GeomFromText('{wkt_line(r['pts'])}', 4326))" for r in roads) + ";")
    for chunk in range(0, len(buildings), 500):
        part = buildings[chunk:chunk + 500]
        lines.append("INSERT INTO building (id, name, use_type, structure, floors, basement_floors, height_m, built_year, region_id, geom) VALUES")
        lines.append(",\n".join(
            f"({b.id}, {sql_str(b.name)}, {sql_str(b.use_type)}, {sql_str(b.structure)}, {b.floors}, {b.basement_floors}, {b.height_m}, {b.built_year}, {b.region_id}, ST_GeomFromText('{wkt_polygon(b.ring)}', 4326))"
            for b in part) + ";")
    lines.append("INSERT INTO facility (id, type, subtype, name, status, installed_year, properties, geom) VALUES")
    lines.append(",\n".join(
        f"({f.id}, {sql_str(f.type)}, {sql_str(f.subtype)}, {sql_str(f.name)}, {sql_str(f.status)}, {f.installed_year}, "
        f"{sql_str(json.dumps(f.extra, ensure_ascii=False))}::jsonb, ST_GeomFromText('{wkt_point(*to_lonlat(f.x, f.y))}', 4326))"
        for f in facilities) + ";")
    lines.append("INSERT INTO bus_route (id, name, geom) VALUES")
    lines.append(",\n".join(
        f"({r['id']}, {sql_str(r['name'])}, ST_GeomFromText('{wkt_line([to_lonlat(x, y) for x, y in r['local']])}', 4326))" for r in bus_routes) + ";")
    lines.append("INSERT INTO sensor (id, code, kind, facility_id, route_id, properties) VALUES")
    lines.append(",\n".join(
        f"({s['id']}, {sql_str(s['code'])}, {sql_str(s['kind'])}, {s['facility_id'] if s['facility_id'] else 'NULL'}, "
        f"{s['route_id'] if s['route_id'] else 'NULL'}, {sql_str(json.dumps({'base': s['base'], 'offset': s.get('offset', 0)}))}::jsonb)"
        for s in sensors) + ";")
    for table in ("region", "road", "building", "facility", "bus_route", "sensor"):
        lines.append(f"SELECT setval(pg_get_serial_sequence('{table}', 'id'), (SELECT max(id) FROM {table}));")
    lines.append("COMMIT;")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_geojson(out: Path, roads, buildings, facilities):
    out.mkdir(parents=True, exist_ok=True)

    def fc(features):
        return {"type": "FeatureCollection", "features": features}

    (out / "regions.geojson").write_text(json.dumps(fc([
        {"type": "Feature", "id": r.id, "properties": {"code": r.code, "name": r.name, "zone_type": r.zone_type},
         "geometry": {"type": "Polygon", "coordinates": [rect(r.x0, r.y0, r.x1, r.y1)]}} for r in REGIONS]), ensure_ascii=False))
    (out / "buildings.geojson").write_text(json.dumps(fc([
        {"type": "Feature", "id": b.id, "properties": {"name": b.name, "use_type": b.use_type, "structure": b.structure,
                                                         "floors": b.floors, "height_m": b.height_m, "built_year": b.built_year,
                                                         "region_id": b.region_id},
         "geometry": {"type": "Polygon", "coordinates": [b.ring]}} for b in buildings]), ensure_ascii=False))
    (out / "facilities.geojson").write_text(json.dumps(fc([
        {"type": "Feature", "id": f.id, "properties": {"type": f.type, "subtype": f.subtype, "name": f.name, "status": f.status},
         "geometry": {"type": "Point", "coordinates": list(to_lonlat(f.x, f.y))}} for f in facilities]), ensure_ascii=False))
    (out / "roads.geojson").write_text(json.dumps(fc([
        {"type": "Feature", "id": r["id"], "properties": {"name": r["name"], "width_m": r["width_m"]},
         "geometry": {"type": "LineString", "coordinates": r["pts"]}} for r in roads]), ensure_ascii=False))


# ---------------------------------------------------------------------------
# 샘플 GLB(랜드마크 타워) 생성: 층이 줄어드는 박스 4개 + 첨탑
# ---------------------------------------------------------------------------

def box_mesh(cx, cz, w, d, y0, y1):
    x0, x1, z0, z1 = cx - w / 2, cx + w / 2, cz - d / 2, cz + d / 2
    faces = [
        ((0, 0, 1), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]),
        ((0, 0, -1), [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)]),
        ((1, 0, 0), [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)]),
        ((-1, 0, 0), [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)]),
        ((0, 1, 0), [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)]),
        ((0, -1, 0), [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)]),
    ]
    pos, nor, idx = [], [], []
    for n, quad in faces:
        base = len(pos)
        pos.extend(quad)
        nor.extend([n] * 4)
        idx.extend([base, base + 1, base + 2, base, base + 2, base + 3])
    return pos, nor, idx


def write_glb(path: Path):
    parts = [
        (box_mesh(0, 0, 40, 40, 0, 60), (0.55, 0.62, 0.70, 1.0)),
        (box_mesh(0, 0, 32, 32, 60, 120), (0.45, 0.55, 0.68, 1.0)),
        (box_mesh(0, 0, 24, 24, 120, 170), (0.40, 0.50, 0.66, 1.0)),
        (box_mesh(0, 0, 14, 14, 170, 195), (0.85, 0.65, 0.30, 1.0)),
        (box_mesh(0, 0, 2, 2, 195, 230), (0.9, 0.9, 0.9, 1.0)),
    ]
    bin_chunk = bytearray()
    buffer_views, accessors, meshes, nodes, materials = [], [], [], [], []

    def add_view(data: bytes, target: int) -> int:
        while len(bin_chunk) % 4:
            bin_chunk.append(0)
        buffer_views.append({"buffer": 0, "byteOffset": len(bin_chunk), "byteLength": len(data), "target": target})
        bin_chunk.extend(data)
        return len(buffer_views) - 1

    for i, ((pos, nor, idx), color) in enumerate(parts):
        pv = add_view(b"".join(struct.pack("<3f", *p) for p in pos), 34962)
        nv = add_view(b"".join(struct.pack("<3f", *n) for n in nor), 34962)
        iv = add_view(b"".join(struct.pack("<H", k) for k in idx), 34963)
        mins = [min(p[a] for p in pos) for a in range(3)]
        maxs = [max(p[a] for p in pos) for a in range(3)]
        accessors.append({"bufferView": pv, "componentType": 5126, "count": len(pos), "type": "VEC3", "min": mins, "max": maxs})
        accessors.append({"bufferView": nv, "componentType": 5126, "count": len(nor), "type": "VEC3"})
        accessors.append({"bufferView": iv, "componentType": 5123, "count": len(idx), "type": "SCALAR"})
        materials.append({"name": f"floor-band-{i}", "pbrMetallicRoughness": {"baseColorFactor": list(color), "metallicFactor": 0.2, "roughnessFactor": 0.6}})
        meshes.append({"name": f"section-{i}", "primitives": [{"attributes": {"POSITION": 3 * i, "NORMAL": 3 * i + 1}, "indices": 3 * i + 2, "material": i}]})
        nodes.append({"name": f"section-{i}", "mesh": i})

    gltf = {
        "asset": {"version": "2.0", "generator": "TwinPilot synthetic generator"},
        "scene": 0,
        "scenes": [{"name": "LandmarkTower", "nodes": list(range(len(nodes)))}],
        "nodes": nodes, "meshes": meshes, "materials": materials,
        "accessors": accessors, "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(bin_chunk)}],
    }
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode()
    json_bytes += b" " * ((4 - len(json_bytes) % 4) % 4)
    while len(bin_chunk) % 4:
        bin_chunk.append(0)
    total = 12 + 8 + len(json_bytes) + 8 + len(bin_chunk)
    out = struct.pack("<4sII", b"glTF", 2, total)
    out += struct.pack("<I4s", len(json_bytes), b"JSON") + json_bytes
    out += struct.pack("<I4s", len(bin_chunk), b"BIN\x00") + bytes(bin_chunk)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    roads, buildings, facilities, sensors, bus_routes = generate(args.seed)
    write_sql(ROOT / "backend/src/main/resources/db/seed/seed.sql", roads, buildings, facilities, sensors, bus_routes)
    write_geojson(ROOT / "data/generated", roads, buildings, facilities)
    write_glb(ROOT / "data/sample-models/landmark-tower.glb")
    counts = {}
    for f in facilities:
        counts[f.type] = counts.get(f.type, 0) + 1
    print(f"regions={len(REGIONS)} roads={len(roads)} buildings={len(buildings)} facilities={counts} sensors={len(sensors)}")


if __name__ == "__main__":
    main()
