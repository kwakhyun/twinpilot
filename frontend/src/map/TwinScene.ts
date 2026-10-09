import {
  BoundingSphere,
  buildModuleUrl,
  Cartesian2,
  Cartesian3,
  Color,
  CesiumWidget,
  ColorGeometryInstanceAttribute,
  CustomDataSource,
  EllipseGeometry,
  GeometryInstance,
  HeadingPitchRange,
  HeadingPitchRoll,
  ImageryLayer,
  JulianDate,
  LabelCollection,
  LabelStyle,
  Math as CesiumMath,
  Model,
  PerInstanceColorAppearance,
  PointPrimitiveCollection,
  PolygonGeometry,
  PolygonHierarchy,
  Primitive,
  Resource,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  ShowGeometryInstanceAttribute,
  TileMapServiceImageryProvider,
  Transforms,
  UrlTemplateImageryProvider,
  VerticalOrigin,
} from "@cesium/engine";
import type {
  BuildingProps,
  ColorMode,
  CoverageResult,
  FacilityProps,
  FeatureCollection,
  GeoJSONGeometry,
  IsochroneCollection,
  ModelAsset,
  RecommendationResult,
  RegionProps,
  SensorReading,
} from "../api/types";
import { buildingColor, DIMMED, HIGHLIGHT, isochroneColor, STATUS } from "./colors";
import { centerOf, pointInGeometry, polygonsOf, ringCentroid } from "./geo";

export type PickInfo =
  | { kind: "building"; id: number; props: BuildingProps }
  | { kind: "facility"; id: number; props: FacilityProps }
  | { kind: "sensor"; reading: SensorReading }
  | { kind: "site"; rank: number; text: string };

const SURFACE = Color.fromCssColorString("#111214");
const LABEL_BG = Color.fromCssColorString("#1a1a19").withAlpha(0.82);
const FACILITY_STYLE: Record<string, { color: string; size: number }> = {
  HYDRANT: { color: "#e66767", size: 7 },
  AED: { color: "#199e70", size: 7 },
  FIRE_STATION: { color: "#c98500", size: 14 },
};
const AQ_STATUS_COLOR: Record<string, string> = {
  GOOD: STATUS.good,
  NORMAL: "#c3c2b7",
  BAD: STATUS.serious,
  VERY_BAD: STATUS.critical,
};

/**
 * Cesium 장면 관리(명령형). React 컴포넌트는 데이터가 바뀔 때 이 클래스의 메서드만 호출한다.
 * - 건물: Primitive 하나에 GeometryInstance를 배치(드로우콜 최소화), 색상·표시는 인스턴스 속성만 갱신
 * - requestRenderMode: 변화가 있을 때만 렌더링해 GPU·배터리 사용을 줄인다
 */
export class TwinScene {
  // Viewer(위젯 패키지)는 Knockout을 포함해 eval이 필요하므로 엄격한 CSP에서 쓸 수 없다. 엔진의 CesiumWidget만 쓴다.
  readonly viewer: CesiumWidget;
  private buildingPrimitive: Primitive | null = null;
  private buildingProps = new Map<number, BuildingProps>();
  private buildingInstanceIds = new Map<number, string[]>();
  private buildingCenters = new Map<number, { lon: number; lat: number }>();
  private pendingStyle: (() => void) | null = null;
  private regions = new CustomDataSource("regions");
  private roads = new CustomDataSource("roads");
  private busRoutes = new CustomDataSource("busRoutes");
  private overlays = new CustomDataSource("coverage");
  private sites = new CustomDataSource("sites");
  private facilityPoints: PointPrimitiveCollection;
  private facilityLabels: LabelCollection;
  private coverageCircles: Primitive | null = null;
  private isochrones: Primitive | null = null;
  private sensorPoints: PointPrimitiveCollection;
  private sensorLabels: LabelCollection;
  private facilityFeatures: FeatureCollection<GeoJSONGeometry, FacilityProps> | null = null;
  private regionFeatures: FeatureCollection<GeoJSONGeometry, RegionProps> | null = null;
  private facilityVisibility = { HYDRANT: true, AED: false, FIRE_STATION: true };
  private models = new Map<string, { key: string; model: Model | null }>();
  private osmLayer: ImageryLayer | null = null;
  private handler: ScreenSpaceEventHandler;
  private destroyed = false;

  constructor(container: HTMLElement) {
    this.viewer = new CesiumWidget(container, {
      baseLayer: false,
      requestRenderMode: true,
      maximumRenderTimeChange: Infinity,
      msaaSamples: 4,
    });
    const scene = this.viewer.scene;
    scene.globe.baseColor = SURFACE;
    scene.backgroundColor = SURFACE;
    scene.globe.showGroundAtmosphere = false;
    scene.fog.enabled = false;
    if (scene.skyAtmosphere) scene.skyAtmosphere.show = true;
    // 하지 정오(KST) 태양광으로 고정해 언제 봐도 같은 음영이 나오게 한다.
    this.viewer.clock.currentTime = JulianDate.fromIso8601("2026-06-21T03:00:00Z");
    this.viewer.clock.shouldAnimate = false;
    scene.screenSpaceCameraController.minimumZoomDistance = 30;

    // 오프라인에서도 쓰는 저해상도 배경(Cesium 내장 NaturalEarthII). 공급자는 비동기로 준비된다.
    const base = ImageryLayer.fromProviderAsync(
      TileMapServiceImageryProvider.fromUrl(buildModuleUrl("Assets/Textures/NaturalEarthII")),
      { brightness: 0.35 },
    );
    base.readyEvent.addEventListener(() => this.render());
    this.viewer.imageryLayers.add(base, 0);

    for (const ds of [this.roads, this.regions, this.busRoutes, this.overlays, this.sites]) {
      void this.viewer.dataSources.add(ds);
    }
    this.facilityPoints = scene.primitives.add(new PointPrimitiveCollection());
    this.facilityLabels = scene.primitives.add(new LabelCollection());
    this.sensorPoints = scene.primitives.add(new PointPrimitiveCollection());
    this.sensorLabels = scene.primitives.add(new LabelCollection());
    this.handler = new ScreenSpaceEventHandler(scene.canvas);

    // 비동기 Primitive 생성이 끝날 때까지 프레임을 요청하고, 끝나면 보류 중인 스타일을 적용한다.
    scene.postRender.addEventListener(() => {
      if (this.buildingPrimitive && !this.buildingPrimitive.ready) {
        scene.requestRender();
      } else if (this.buildingPrimitive?.ready && this.pendingStyle) {
        const apply = this.pendingStyle;
        this.pendingStyle = null;
        apply();
        scene.requestRender();
      }
    });
  }

  render() {
    if (!this.destroyed) this.viewer.scene.requestRender();
  }

  destroy() {
    this.destroyed = true;
    this.handler.destroy();
    this.viewer.destroy();
  }

  // ---------------------------------------------------------------- camera
  setInitialView() {
    this.viewer.camera.setView({
      destination: Cartesian3.fromDegrees(129.0545, 35.1395, 1450),
      orientation: { heading: CesiumMath.toRadians(8), pitch: CesiumMath.toRadians(-38), roll: 0 },
    });
    this.render();
  }

  flyTo(lon: number, lat: number, height = 520) {
    const offsetLat = height / 110_540 / Math.tan(CesiumMath.toRadians(40));
    this.viewer.camera.flyTo({
      destination: Cartesian3.fromDegrees(lon, lat - offsetLat, height),
      orientation: { heading: 0, pitch: CesiumMath.toRadians(-40), roll: 0 },
      duration: 1.4,
    });
  }

  flyToBuildings(ids: number[]) {
    const pts = ids.map((id) => this.buildingCenters.get(id)).filter((p): p is { lon: number; lat: number } => !!p);
    const c = centerOf(pts);
    if (!c) return;
    const sphere = new BoundingSphere(Cartesian3.fromDegrees(c.lon, c.lat, 20), c.radiusM);
    this.viewer.camera.flyToBoundingSphere(sphere, {
      offset: new HeadingPitchRange(0, CesiumMath.toRadians(-45), Math.max(400, c.radiusM * 3.2)),
      duration: 1.4,
    });
  }

  // ---------------------------------------------------------------- base layers
  setOsm(visible: boolean) {
    if (visible && !this.osmLayer) {
      this.osmLayer = this.viewer.imageryLayers.addImageryProvider(
        new UrlTemplateImageryProvider({
          url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
          maximumLevel: 19,
          credit: "© OpenStreetMap contributors",
        }),
      );
      this.osmLayer.brightness = 0.55;
      this.osmLayer.saturation = 0.3;
    } else if (!visible && this.osmLayer) {
      this.viewer.imageryLayers.remove(this.osmLayer);
      this.osmLayer = null;
    }
    this.render();
  }

  setRegions(fc: FeatureCollection<GeoJSONGeometry, RegionProps>) {
    this.regionFeatures = fc;
    this.regions.entities.removeAll();
    for (const f of fc.features) {
      for (const rings of polygonsOf(f.geometry)) {
        this.regions.entities.add({
          polyline: {
            positions: Cartesian3.fromDegreesArray(rings[0].flat()),
            width: 2,
            material: Color.WHITE.withAlpha(0.35),
          },
        });
      }
      const c = ringCentroid(f.geometry);
      if (c) {
        this.regions.entities.add({
          position: Cartesian3.fromDegrees(c.lon, c.lat, 140),
          label: {
            text: `${f.properties.name} · ${zoneLabel(f.properties.zoneType)}`,
            font: "600 14px system-ui, sans-serif",
            fillColor: Color.WHITE.withAlpha(0.95),
            showBackground: true,
            backgroundColor: LABEL_BG,
            backgroundPadding: new Cartesian2(8, 5),
            verticalOrigin: VerticalOrigin.BOTTOM,
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
          },
        });
      }
    }
    this.render();
  }

  setRoads(fc: FeatureCollection<GeoJSONGeometry, { name: string; widthM: number }>) {
    this.roads.entities.removeAll();
    for (const f of fc.features) {
      if (f.geometry.type !== "LineString") continue;
      this.roads.entities.add({
        corridor: {
          positions: Cartesian3.fromDegreesArray(f.geometry.coordinates.flat()),
          width: f.properties.widthM,
          height: 0.1,
          material: Color.fromCssColorString(f.properties.widthM > 20 ? "#33363d" : "#272a30"),
        },
      });
    }
    this.render();
  }

  setBusRoutes(fc: FeatureCollection<GeoJSONGeometry, { name: string }>) {
    this.busRoutes.entities.removeAll();
    for (const f of fc.features) {
      if (f.geometry.type !== "LineString") continue;
      this.busRoutes.entities.add({
        polyline: {
          positions: Cartesian3.fromDegreesArrayHeights(f.geometry.coordinates.flatMap(([x, y]) => [x, y, 3])),
          width: 3,
          material: Color.fromCssColorString("#d55181").withAlpha(0.85),
        },
      });
    }
    this.render();
  }

  setLayerVisible(layer: "regions" | "roads" | "busRoutes" | "buildings" | "sensors" | "models", visible: boolean) {
    if (layer === "regions") this.regions.show = visible;
    if (layer === "roads") this.roads.show = visible;
    if (layer === "busRoutes") this.busRoutes.show = visible;
    if (layer === "buildings" && this.buildingPrimitive) this.buildingPrimitive.show = visible;
    if (layer === "sensors") {
      this.sensorPoints.show = visible;
      this.sensorLabels.show = visible;
    }
    if (layer === "models") this.models.forEach((m) => m.model && (m.model.show = visible));
    this.render();
  }

  // ---------------------------------------------------------------- buildings
  setBuildings(fc: FeatureCollection<GeoJSONGeometry, BuildingProps>, mode: ColorMode) {
    if (this.buildingPrimitive) {
      this.viewer.scene.primitives.remove(this.buildingPrimitive);
    }
    this.buildingProps.clear();
    this.buildingInstanceIds.clear();
    const instances: GeometryInstance[] = [];
    for (const f of fc.features) {
      this.buildingProps.set(f.id, f.properties);
      const center = ringCentroid(f.geometry);
      if (center) this.buildingCenters.set(f.id, center);
      const color = Color.fromCssColorString(buildingColor(mode, f.properties));
      polygonsOf(f.geometry).forEach((rings, part) => {
        const instanceId = `b:${f.id}:${part}`;
        const ids = this.buildingInstanceIds.get(f.id) ?? [];
        ids.push(instanceId);
        this.buildingInstanceIds.set(f.id, ids);
        instances.push(
          new GeometryInstance({
            id: instanceId,
            geometry: new PolygonGeometry({
              polygonHierarchy: new PolygonHierarchy(
                Cartesian3.fromDegreesArray(rings[0].slice(0, -1).flat()),
                rings.slice(1).map((h) => new PolygonHierarchy(Cartesian3.fromDegreesArray(h.slice(0, -1).flat()))),
              ),
              height: 0,
              extrudedHeight: Math.max(f.properties.heightM, 2),
              vertexFormat: PerInstanceColorAppearance.VERTEX_FORMAT,
            }),
            attributes: {
              color: ColorGeometryInstanceAttribute.fromColor(color),
              show: new ShowGeometryInstanceAttribute(true),
            },
          }),
        );
      });
    }
    this.buildingPrimitive = this.viewer.scene.primitives.add(
      new Primitive({
        geometryInstances: instances,
        appearance: new PerInstanceColorAppearance({ translucent: false, closed: true }),
        asynchronous: true,
      }),
    );
    this.render();
  }

  /** 색상 모드·하이라이트·선택 상태를 인스턴스 속성에 반영한다(지오메트리 재생성 없음). */
  styleBuildings(mode: ColorMode, highlightIds: number[] | null, selectedId: number | null) {
    const apply = () => {
      const primitive = this.buildingPrimitive;
      if (!primitive) return;
      const highlight = highlightIds ? new Set(highlightIds) : null;
      const dimmed = Color.fromCssColorString(DIMMED);
      const selected = Color.fromCssColorString(HIGHLIGHT);
      const scratch = new Color();
      for (const [id, props] of this.buildingProps) {
        let color: Color;
        if (id === selectedId) color = selected;
        else if (highlight && !highlight.has(id)) color = dimmed;
        else color = Color.fromCssColorString(buildingColor(mode, props), scratch);
        const value = ColorGeometryInstanceAttribute.toValue(color);
        for (const instanceId of this.buildingInstanceIds.get(id) ?? []) {
          const attrs = primitive.getGeometryInstanceAttributes(instanceId);
          if (attrs) attrs.color = value;
        }
      }
    };
    if (this.buildingPrimitive?.ready) {
      apply();
      this.render();
    } else {
      this.pendingStyle = apply;
      this.render();
    }
  }

  buildingCenter(id: number) {
    return this.buildingCenters.get(id) ?? null;
  }

  // ---------------------------------------------------------------- facilities
  setFacilities(fc: FeatureCollection<GeoJSONGeometry, FacilityProps>) {
    this.facilityFeatures = fc;
    this.facilityPoints.removeAll();
    this.facilityLabels.removeAll();
    for (const f of fc.features) {
      if (f.geometry.type !== "Point") continue;
      const [lon, lat] = f.geometry.coordinates;
      const style = FACILITY_STYLE[f.properties.type];
      const planned = f.properties.status === "PLANNED";
      const inactive = f.properties.status === "INACTIVE";
      this.facilityPoints.add({
        id: { kind: "facility", id: f.id, props: f.properties },
        position: Cartesian3.fromDegrees(lon, lat, 3),
        pixelSize: planned ? style.size + 5 : style.size,
        color: Color.fromCssColorString(planned ? HIGHLIGHT : inactive ? "#6b6b66" : style.color),
        outlineColor: planned ? Color.fromCssColorString(style.color) : SURFACE,
        outlineWidth: planned ? 3 : 1.5,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
        show: this.facilityVisibility[f.properties.type],
      });
      if (f.properties.type === "FIRE_STATION") {
        this.facilityLabels.add({
          position: Cartesian3.fromDegrees(lon, lat, 3),
          text: f.properties.name,
          font: "600 13px system-ui, sans-serif",
          fillColor: Color.WHITE,
          outlineColor: SURFACE,
          outlineWidth: 4,
          style: LabelStyle.FILL_AND_OUTLINE,
          pixelOffset: new Cartesian2(0, -18),
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
          show: this.facilityVisibility.FIRE_STATION,
        });
      }
    }
    this.render();
  }

  setFacilityVisibility(v: { HYDRANT: boolean; AED: boolean; FIRE_STATION: boolean }) {
    this.facilityVisibility = v;
    for (let i = 0; i < this.facilityPoints.length; i++) {
      const p = this.facilityPoints.get(i);
      p.show = v[(p.id as { props: FacilityProps }).props.type];
    }
    for (let i = 0; i < this.facilityLabels.length; i++) {
      this.facilityLabels.get(i).show = v.FIRE_STATION;
    }
    this.render();
  }

  // ---------------------------------------------------------------- analysis overlays
  /** 출동 도달권(분 단위 등시간선 폴리곤). 큰 범위부터 옅게 겹쳐 그려 안쪽(짧은 시간)이 진해진다. */
  showIsochrones(fc: IsochroneCollection | null, visible = true) {
    if (this.isochrones) {
      this.viewer.scene.primitives.remove(this.isochrones);
      this.isochrones = null;
    }
    if (fc && visible) {
      const features = [...fc.features].sort((a, b) => b.properties.minutes - a.properties.minutes);
      const minutes = features.map((f) => f.properties.minutes);
      const instances: GeometryInstance[] = [];
      features.forEach((f, order) => {
        if (!f.geometry) return;
        const color = ColorGeometryInstanceAttribute.fromColor(
          Color.fromCssColorString(isochroneColor(f.properties.minutes, minutes)).withAlpha(0.22),
        );
        for (const rings of polygonsOf(f.geometry)) {
          instances.push(
            new GeometryInstance({
              geometry: new PolygonGeometry({
                polygonHierarchy: new PolygonHierarchy(
                  Cartesian3.fromDegreesArray(rings[0].slice(0, -1).flat()),
                  rings.slice(1).map((h) => new PolygonHierarchy(Cartesian3.fromDegreesArray(h.slice(0, -1).flat()))),
                ),
                height: 0.25 + order * 0.05,
                vertexFormat: PerInstanceColorAppearance.VERTEX_FORMAT,
              }),
              attributes: { color },
            }),
          );
        }
      });
      if (instances.length > 0) {
        this.isochrones = this.viewer.scene.primitives.add(
          new Primitive({
            geometryInstances: instances,
            appearance: new PerInstanceColorAppearance({ translucent: true, flat: true }),
            asynchronous: false,
          }),
        );
      }
    }
    this.render();
  }

  showCoverage(result: CoverageResult | null) {
    this.overlays.entities.removeAll();
    if (this.coverageCircles) {
      this.viewer.scene.primitives.remove(this.coverageCircles);
      this.coverageCircles = null;
    }
    if (!result || !this.facilityFeatures) {
      this.render();
      return;
    }
    // 시설별 반경: 시설이 속한 행정동의 기준 반경(사용자 지정 시 그 값)
    const radiusByRegion = new Map(result.regions.map((r) => [r.regionId, r.radiusM]));
    const fallback = result.radiusOverrideM ?? result.regions[0]?.radiusM ?? 100;
    const statuses = result.includePlanned ? ["ACTIVE", "PLANNED"] : ["ACTIVE"];
    const instances: GeometryInstance[] = [];
    const wash = ColorGeometryInstanceAttribute.fromColor(Color.fromCssColorString("#3987e5").withAlpha(0.1));
    for (const f of this.facilityFeatures.features) {
      if (f.geometry.type !== "Point" || f.properties.type !== result.facilityType) continue;
      if (!statuses.includes(f.properties.status)) continue;
      const [lon, lat] = f.geometry.coordinates;
      const region = this.regionFeatures?.features.find((r) => pointInGeometry(lon, lat, r.geometry));
      const radius = result.radiusOverrideM ?? (region ? radiusByRegion.get(region.id) : undefined) ?? fallback;
      instances.push(
        new GeometryInstance({
          geometry: new EllipseGeometry({
            center: Cartesian3.fromDegrees(lon, lat),
            semiMajorAxis: radius,
            semiMinorAxis: radius,
            height: 0.4,
            vertexFormat: PerInstanceColorAppearance.VERTEX_FORMAT,
          }),
          attributes: { color: wash },
        }),
      );
    }
    if (instances.length > 0) {
      this.coverageCircles = this.viewer.scene.primitives.add(
        new Primitive({
          geometryInstances: instances,
          appearance: new PerInstanceColorAppearance({ translucent: true, flat: true }),
          asynchronous: false,
        }),
      );
    }
    for (const spot of result.blindSpots) {
      for (const rings of polygonsOf(spot.geometry)) {
        this.overlays.entities.add({
          polygon: {
            hierarchy: new PolygonHierarchy(
              Cartesian3.fromDegreesArray(rings[0].slice(0, -1).flat()),
              rings.slice(1).map((h) => new PolygonHierarchy(Cartesian3.fromDegreesArray(h.slice(0, -1).flat()))),
            ),
            height: 0.6,
            material: Color.fromCssColorString(STATUS.critical).withAlpha(0.28),
          },
        });
      }
    }
    this.render();
  }

  showSites(result: RecommendationResult | null) {
    this.sites.entities.removeAll();
    if (result) {
      for (const s of result.sites) {
        this.sites.entities.add({
          position: Cartesian3.fromDegrees(s.lon, s.lat, 4),
          point: {
            pixelSize: 16,
            color: Color.fromCssColorString(HIGHLIGHT),
            outlineColor: Color.fromCssColorString(FACILITY_STYLE[result.facilityType]?.color ?? "#e66767"),
            outlineWidth: 4,
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
          },
          label: {
            text: `추천 ${s.rank} · +${s.newlyCoveredBuildings}동`,
            font: "700 14px system-ui, sans-serif",
            fillColor: Color.WHITE,
            outlineColor: SURFACE,
            outlineWidth: 4,
            style: LabelStyle.FILL_AND_OUTLINE,
            pixelOffset: new Cartesian2(0, -22),
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
          },
          ellipse: {
            semiMajorAxis: s.radiusM,
            semiMinorAxis: s.radiusM,
            height: 0.8,
            material: Color.WHITE.withAlpha(0.12),
            outline: true,
            outlineColor: Color.WHITE.withAlpha(0.7),
          },
          properties: { siteRank: s.rank, text: `추천 입지 ${s.rank}: 신규 커버 ${s.newlyCoveredBuildings}동` },
        });
      }
    }
    this.render();
  }

  // ---------------------------------------------------------------- sensors
  updateSensors(readings: SensorReading[]) {
    this.sensorPoints.removeAll();
    this.sensorLabels.removeAll();
    for (const r of readings) {
      const alert = r.status === "ALERT";
      if (r.kind === "HYDRANT_PRESSURE" && !alert) continue; // 정상 수압 센서는 소화전 점과 겹치므로 생략
      const color = r.kind === "AIR_QUALITY" ? AQ_STATUS_COLOR[r.status] : STATUS.critical;
      this.sensorPoints.add({
        id: { kind: "sensor", reading: r },
        position: Cartesian3.fromDegrees(r.lon, r.lat, 6),
        pixelSize: alert ? 15 : 11,
        color: Color.fromCssColorString(color),
        outlineColor: Color.WHITE,
        outlineWidth: 2,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      });
      if (alert || r.status === "BAD" || r.status === "VERY_BAD") {
        const text =
          r.kind === "AIR_QUALITY"
            ? `${r.code} PM2.5 ${r.values.pm25}`
            : `⚠ ${r.code} 수압 ${r.values.pressureMpa}MPa`;
        this.sensorLabels.add({
          position: Cartesian3.fromDegrees(r.lon, r.lat, 6),
          text,
          font: "600 12px system-ui, sans-serif",
          fillColor: Color.WHITE,
          showBackground: true,
          backgroundColor: r.kind === "AIR_QUALITY" ? LABEL_BG : Color.fromCssColorString(STATUS.critical).withAlpha(0.85),
          pixelOffset: new Cartesian2(0, -20),
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        });
      }
    }
    this.render();
  }

  // ---------------------------------------------------------------- 3D models
  /** fileResource: 인증 헤더를 포함한 모델 파일 요청(url, headers)을 돌려준다. */
  async setModels(
    models: ModelAsset[],
    fileResource: (id: string) => Promise<{ url: string; headers: Record<string, string> }>,
    visible: boolean,
  ) {
    const wanted = new Map(
      models
        .filter((m) => m.status === "READY" && m.lon != null && m.lat != null)
        .map((m) => [m.id, m] as const),
    );
    for (const [id, entry] of this.models) {
      if (!wanted.has(id)) {
        if (entry.model) this.viewer.scene.primitives.remove(entry.model);
        this.models.delete(id);
      }
    }
    for (const m of wanted.values()) {
      const key = `${m.lon},${m.lat},${m.heightM},${m.headingDeg},${m.scale}`;
      const current = this.models.get(m.id);
      if (current && current.key === key) continue;
      if (current?.model) this.viewer.scene.primitives.remove(current.model);
      this.models.set(m.id, { key, model: null });
      const origin = Cartesian3.fromDegrees(m.lon!, m.lat!, m.heightM);
      const modelMatrix = Transforms.headingPitchRollToFixedFrame(
        origin,
        new HeadingPitchRoll(CesiumMath.toRadians(m.headingDeg), 0, 0),
      );
      let model: Model;
      try {
        const file = await fileResource(m.id);
        model = await Model.fromGltfAsync({
          url: new Resource({ url: file.url, headers: file.headers }),
          modelMatrix,
          scale: m.scale,
          id: { kind: "model", id: m.id },
        });
      } catch (e) {
        console.warn(`모델 ${m.id} 불러오기 실패`, e);
        this.models.delete(m.id);
        continue;
      }
      if (this.destroyed || this.models.get(m.id)?.key !== key) {
        model.destroy();
        continue;
      }
      model.show = visible;
      this.viewer.scene.primitives.add(model);
      this.models.set(m.id, { key, model });
      // 모델 로딩(텍스처·셰이더)이 끝날 때까지 프레임 요청
      const tick = () => {
        this.render();
        if (!model.ready && !this.destroyed) setTimeout(tick, 100);
      };
      tick();
    }
  }

  /** 화면 중심의 지표 좌표(모델을 현재 화면에 배치할 때 사용). */
  screenCenter(): { lon: number; lat: number } | null {
    const canvas = this.viewer.scene.canvas;
    const ray = this.viewer.camera.getPickRay(new Cartesian2(canvas.clientWidth / 2, canvas.clientHeight / 2));
    const hit = ray && this.viewer.scene.globe.pick(ray, this.viewer.scene);
    if (!hit) return null;
    const c = this.viewer.scene.globe.ellipsoid.cartesianToCartographic(hit);
    return { lon: CesiumMath.toDegrees(c.longitude), lat: CesiumMath.toDegrees(c.latitude) };
  }

  // ---------------------------------------------------------------- picking
  private pick(position: Cartesian2): PickInfo | null {
    const picked = this.viewer.scene.pick(position);
    if (!picked) return null;
    const id = picked.id;
    if (typeof id === "string" && id.startsWith("b:")) {
      const buildingId = Number(id.split(":")[1]);
      const props = this.buildingProps.get(buildingId);
      return props ? { kind: "building", id: buildingId, props } : null;
    }
    if (id && typeof id === "object") {
      if (id.kind === "facility") return { kind: "facility", id: id.id, props: id.props };
      if (id.kind === "sensor") return { kind: "sensor", reading: id.reading };
      const rank = id.properties?.siteRank?.getValue?.();
      if (rank) return { kind: "site", rank, text: id.properties.text.getValue() };
    }
    return null;
  }

  onClick(cb: (info: PickInfo | null, screen: { x: number; y: number }) => void) {
    this.handler.setInputAction((e: { position: Cartesian2 }) => {
      cb(this.pick(e.position), { x: e.position.x, y: e.position.y });
    }, ScreenSpaceEventType.LEFT_CLICK);
  }

  /** 마우스 이동 피킹은 프레임당 한 번으로 제한한다. */
  onHover(cb: (info: PickInfo | null, screen: { x: number; y: number }) => void) {
    let pending: Cartesian2 | null = null;
    this.handler.setInputAction((e: { endPosition: Cartesian2 }) => {
      const first = pending === null;
      pending = Cartesian2.clone(e.endPosition);
      if (!first) return;
      requestAnimationFrame(() => {
        const pos = pending!;
        pending = null;
        if (!this.destroyed) cb(this.pick(pos), { x: pos.x, y: pos.y });
      });
    }, ScreenSpaceEventType.MOUSE_MOVE);
  }
}

function zoneLabel(zone: string): string {
  return { RESIDENTIAL: "주거지역", COMMERCIAL: "상업지역", INDUSTRIAL: "공업지역", GREEN: "녹지지역" }[zone] ?? zone;
}
