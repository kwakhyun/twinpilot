# 런북: 실제 데이터 적재

합성 데이터 대신 기관 데이터(건축물, 소방용수시설, AED, 소방서, 도로, 행정동 경계)를 넣는 절차다.
관리 화면(ADMIN → 데이터 가져오기) 또는 API(`POST /api/v1/admin/imports`)를 쓴다.

## 1. 원본 준비

| 레이어 | 도형 | 필수·주요 속성(매핑 대상) |
|---|---|---|
| BUILDING | Polygon·MultiPolygon | `externalId`, `name`, `useType`, `structure`, `floors`, `basementFloors`, `heightM`, `builtYear` |
| HYDRANT·AED·FIRE_STATION | Point | `externalId`, `name`, `subtype`, `status`, `installedYear` |
| ROAD | LineString·MultiLineString | `externalId`, `name`, `widthM` |
| REGION | Polygon·MultiPolygon | `externalId`, `name`, `code`, `zoneType` |

- 형식: GeoJSON FeatureCollection(최대 200MB, `IMPORT_MAX_FEATURES` 기본 50만 피처). Shapefile 등은 ogr2ogr로 바꾼다:
  `ogr2ogr -f GeoJSON -lco RFC7946=NO buildings.geojson 원본.shp`
- 좌표계: `srid`로 지정한다(허용: 4326, 3857, 5174, 5179, 5181, 5185, 5186, 5187, 5188). 지정하지 않으면 파일의 `crs`, 없으면 4326으로 본다.
  변환 후 대한민국 범위(경도 124~132, 위도 33~39.5) 밖의 피처는 제외하고 보고서에 센다.
- 코드값: `useType`은 RESIDENTIAL_APT·RESIDENTIAL_HOUSE·COMMERCIAL·OFFICE·PUBLIC·SCHOOL·INDUSTRIAL·WAREHOUSE·OTHER,
  `structure`는 RC·STEEL·MASONRY·WOOD, `zoneType`은 RESIDENTIAL·COMMERCIAL·INDUSTRIAL·GREEN·OTHER. 원본 코드는 `values` 매핑으로 바꾼다.

## 2. 매핑 JSON

원본 데이터셋의 항목 정의서를 보고 작성한다. 지정하지 않은 필드는 같은 이름(`useType`, `use_type`)을 찾는다.

```json
{
  "fields":   {"externalId": "BLD_ID", "name": "BLD_NM", "useType": "MAIN_PURPS", "structure": "STRCT",
               "floors": "GRND_FLR", "basementFloors": "UGRND_FLR", "heightM": "HEIGHT", "builtYear": "USE_APR_YR"},
  "values":   {"useType": {"공동주택": "RESIDENTIAL_APT", "단독주택": "RESIDENTIAL_HOUSE", "제1종근린생활시설": "COMMERCIAL"},
               "structure": {"철근콘크리트구조": "RC", "철골구조": "STEEL", "조적구조": "MASONRY", "목구조": "WOOD"}},
  "defaults": {"structure": "RC", "floors": 1},
  "floorHeightM": 3.0
}
```

`heightM`이 없으면 `floors × floorHeightM`으로 채운다.

## 3. 검증(dry run)

```bash
TOKEN=$(python3 e2e/oidc_login.py --base $BASE --user <관리자> --password …)
curl -s -X POST $BASE/api/v1/admin/imports -H "Authorization: Bearer $TOKEN" \
  -F file=@buildings.geojson -F layer=BUILDING -F source=bldg-registry-2026 -F srid=5186 \
  -F mapping="$(cat mapping.json)" -F dryRun=true
# → 202 + Job. 결과: GET /api/v1/analysis/jobs/{id} 의 result
```

보고서의 `total`, `valid`, `invalid`, `outOfBounds`, `fixedGeometries`, `duplicates`, `errors`(예시), `sample`(정규화 결과 예시)을
확인한다. 무효·범위 밖 비율이 높으면 좌표계와 매핑을 다시 본다.

## 4. 적재

| mode | 동작 |
|---|---|
| `UPSERT`(기본) | 같은 출처(`source`)·`externalId`면 갱신, 없으면 추가. 원본에 없는 기존 행은 둔다 |
| `REPLACE` | 같은 출처의 기존 행 중 이번 파일에 없는 행을 지운다(출처가 다른 데이터는 건드리지 않는다) |

```bash
curl … -F dryRun=false -F mode=UPSERT -F refreshRisk=true
```

- `refreshRisk=true`(기본)면 적재 후 도로망 출동 시간과 화재위험도를 다시 계산한다. 여러 레이어를 연달아 넣을 때는
  마지막 레이어에서만 켜거나, 끝난 뒤 분석 Job `REFRESH_RISK`를 한 번 실행한다.
- 권장 순서: REGION → ROAD → FIRE_STATION·HYDRANT·AED → BUILDING(마지막에 위험도 재계산).
- 적재는 감사 로그(`DATA_IMPORT`)에 남고, 끝나면 모든 레플리카의 분석 캐시가 비워진다.
- 15만 피처 기준 검증 7.2초(05 문서). 병합은 한 트랜잭션에서 최대 30분까지 허용한다.

## 5. 합성 데이터 끄기

실제 데이터 적재를 확인한 뒤 `SEED_ENABLED: "false"`로 바꾼다. 시드로 들어간 합성 데이터는 출처(`source`)가 비어 있어(NULL)
`REPLACE` 적재로는 지워지지 않는다. 출처 기준으로 지운다.

```sql
-- 예: 합성 건물·시설 삭제(백업 후 실행)
DELETE FROM building WHERE source IS NULL;
DELETE FROM sensor WHERE facility_id IN (SELECT id FROM facility WHERE source IS NULL);   -- 시설에 연결된 시뮬레이터 센서
DELETE FROM facility WHERE source IS NULL AND status <> 'PLANNED';
```

이후 `REFRESH_RISK` Job을 실행한다.

## 6. 데이터 출처·라이선스

공공데이터는 이용 조건(출처 표시, 변경 금지 여부)을 확인하고 화면 하단이나 문서에 출처를 표시한다.
개인정보나 보안 시설 정보가 섞여 있지 않은지 적재 전에 확인한다.
