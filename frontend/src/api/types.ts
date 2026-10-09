// twin-api(Java) 응답 타입. backend의 DTO record와 1:1로 맞춘다.

export type FacilityType = "HYDRANT" | "AED" | "FIRE_STATION";
export type ColorMode = "risk" | "use" | "age";

export interface Feature<G = GeoJSONGeometry, P = Record<string, unknown>> {
  type: "Feature";
  id: number;
  geometry: G;
  properties: P;
}

export interface FeatureCollection<G = GeoJSONGeometry, P = Record<string, unknown>> {
  type: "FeatureCollection";
  features: Feature<G, P>[];
}

export type Position = [number, number];
export type GeoJSONGeometry =
  | { type: "Point"; coordinates: Position }
  | { type: "LineString"; coordinates: Position[] }
  | { type: "MultiLineString"; coordinates: Position[][] }
  | { type: "Polygon"; coordinates: Position[][] }
  | { type: "MultiPolygon"; coordinates: Position[][][] };

export interface BuildingProps {
  name: string;
  useType: string;
  structure: string;
  floors: number;
  heightM: number;
  builtYear: number | null;
  regionId: number;
  risk: number | null;
}

export interface RegionProps {
  code: string;
  name: string;
  zoneType: string;
  hydrantRadiusM: number;
  areaM2: number;
  buildingCount: number;
}

export interface FacilityProps {
  type: FacilityType;
  subtype: string;
  name: string;
  status: "ACTIVE" | "INACTIVE" | "PLANNED";
  installedYear: number | null;
}

export interface RiskFactors {
  age: number;
  structure: number;
  height: number;
  hydrant: number;
  response: number;
  nearestHydrantM: number;
  nearestStationM: number;
  responseMin: number;
}

export interface BuildingSummary {
  id: number;
  name: string;
  useType: string;
  structure: string;
  floors: number;
  heightM: number;
  builtYear: number | null;
  age: number | null;
  regionId: number;
  regionName: string;
  risk: number | null;
  riskFactors: RiskFactors | null;
  lon: number;
  lat: number;
}

export interface NearbyFacility {
  id: number;
  type: FacilityType;
  subtype: string;
  name: string;
  status: string;
  distanceM: number;
  lon: number;
  lat: number;
}

export interface SensorReading {
  sensorId: number;
  code: string;
  kind: "HYDRANT_PRESSURE" | "AIR_QUALITY";
  facilityId: number | null;
  routeId: number | null;
  lon: number;
  lat: number;
  values: Record<string, number>;
  status: "NORMAL" | "ALERT" | "GOOD" | "BAD" | "VERY_BAD";
  ts: string;
}

export interface BuildingDetail {
  building: BuildingSummary;
  basementFloors: number;
  footprint: GeoJSONGeometry;
  nearestHydrants: NearbyFacility[];
  nearestAeds: NearbyFacility[];
  response: {
    stationName: string;
    straightDistanceM: number;
    estimatedMinutes: number;
    targetMinutes: number;
    withinTarget: boolean;
    method: string;
    straightLineMinutes: number;
    roadAccessM: number | null;
  } | null;
  ladder: { ladderReachM: number; floorHeightM: number; reachableFloor: number; floorsBeyondReach: number };
  hydrantSensors: SensorReading[];
  queryMillis: number;
}

export interface RegionCoverage {
  regionId: number;
  regionName: string;
  zoneType: string;
  radiusM: number;
  totalBuildings: number;
  coveredBuildings: number;
  coverageRate: number;
  uncoveredHighRisk: number;
  uncoveredRiskSum: number;
}

export interface UncoveredBuilding {
  id: number;
  name: string;
  useType: string;
  builtYear: number | null;
  floors: number;
  risk: number | null;
  regionId: number;
  nearestFacilityM: number;
  lon: number;
  lat: number;
}

export interface CoverageSummary {
  totalBuildings: number;
  coveredBuildings: number;
  coverageRate: number;
  uncoveredHighRisk: number;
  blindSpotAreaM2: number;
}

export interface CoverageRequest {
  facilityType: FacilityType;
  radiusM?: number | null;
  regionId?: number | null;
  includePlanned?: boolean;
  includeBlindSpots?: boolean;
  topUncovered?: number;
}

export interface CoverageResult {
  facilityType: FacilityType;
  includePlanned: boolean;
  radiusOverrideM: number | null;
  summary: CoverageSummary;
  regions: RegionCoverage[];
  uncoveredBuildingIds: number[];
  topUncovered: UncoveredBuilding[];
  blindSpots: { regionId: number; areaM2: number; geometry: GeoJSONGeometry }[];
  standard: string;
  elapsedMs: number;
}

export interface Site {
  rank: number;
  lon: number;
  lat: number;
  regionId: number;
  regionName: string;
  radiusM: number;
  newlyCoveredBuildings: number;
  riskCovered: number;
  buildingIds: number[];
}

export interface RecommendationResult {
  facilityType: FacilityType;
  requested: number;
  sites: Site[];
  candidateCount: number;
  totalBuildings: number;
  coverageBefore: number;
  coverageAfter: number;
  newlyCovered: number;
  riskCovered: number;
  method: string;
  elapsedMs: number;
}

export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface JobView<R = unknown> {
  id: string;
  type: string;
  status: JobStatus;
  progress: number;
  message: string;
  params: Record<string, unknown>;
  result: R | null;
  error: string | null;
  createdBy: string | null;
  attempts: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface Me {
  subject: string;
  username: string;
  roles: string[];
}

export interface ResponseResult {
  targetMin: number;
  method: string;
  network: { version: number; nodes: number; edges: number; builtAt: string; stale: boolean } | null;
  summary: {
    totalBuildings: number;
    withinTarget: number;
    withinRate: number;
    beyondTarget: number;
    unreachable: number;
    highRiskBeyond: number;
    farFromRoad: number;
    p50Min: number | null;
    p90Min: number | null;
    maxMin: number | null;
  };
  regions: { regionId: number; regionName: string; total: number; withinTarget: number; withinRate: number; p90Min: number | null }[];
  slowest: {
    id: number;
    name: string;
    useType: string;
    risk: number | null;
    responseMin: number;
    stationName: string | null;
    roadAccessM: number | null;
    lon: number;
    lat: number;
  }[];
  beyondTargetIds: number[];
  elapsedMs: number;
}

export type IsochroneCollection = FeatureCollection<GeoJSONGeometry | null, { minutes: number }>;

export type ImportLayer = "BUILDING" | "HYDRANT" | "AED" | "FIRE_STATION" | "ROAD" | "REGION";

export interface ImportResult {
  layer: ImportLayer;
  source: string;
  mode: "UPSERT" | "REPLACE";
  dryRun: boolean;
  srid: number;
  total: number;
  valid: number;
  invalid: number;
  outOfBounds: number;
  fixedGeometries: number;
  duplicates: number;
  inserted: number;
  updated: number;
  removedOrDeactivated: number;
  errors: string[];
  sample: Record<string, unknown>[];
  elapsedMs: number;
}

export interface AuditEntry {
  id: number;
  ts: string;
  actorSub: string;
  actorName: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  details: Record<string, unknown>;
  clientIp: string | null;
  traceId: string | null;
}

export interface Bucket {
  key: string;
  count: number;
}

export interface StatsSummary {
  totals: { buildings: number; avgRisk: number; highRisk: number; oldBuildings: number; oldRatio: number };
  regions: {
    regionId: number;
    name: string;
    zoneType: string;
    buildings: number;
    avgRisk: number;
    highRisk: number;
    oldBuildings: number;
    oldRatio: number;
  }[];
  builtDecades: Bucket[];
  riskHistogram: Bucket[];
  useTypes: Bucket[];
  facilities: Bucket[];
  hydrantCoverage: CoverageSummary;
  aedCoverage: CoverageSummary;
  oldAgeYears: number;
  highRiskThreshold: number;
}

export interface ModelAsset {
  id: string;
  name: string;
  status: "PROCESSING" | "READY" | "FAILED";
  error: string | null;
  format: string;
  originalFilename: string;
  sizeBytes: number;
  stats: {
    meshes: number;
    primitives: number;
    vertices: number;
    triangles: number;
    materials: number;
    sizeM?: number[];
    warnings: string[];
    storage: string;
    generator?: string;
  } | null;
  lon: number | null;
  lat: number | null;
  heightM: number;
  headingDeg: number;
  scale: number;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}
