import {
  API_ERROR_CODES,
  type ApiError,
  type BoundaryCrossing,
  type BoundaryDirection,
  type BoundaryRole,
  type ExternalHandoff,
  type CameraPage,
  type CameraSnapshotStatus,
  type ControlledArea,
  type HealthResponse,
  type NavigationHandoff,
  type ReadinessResponse,
  type RouteResponse,
  type RoutePlanningMode,
  type RouteSegment,
  type RouteStep,
} from '@/types/api';
import type { CoordinateSystem } from '@/types/coordinate';
import {
  isFiniteNumber,
  isNonNegativeInteger,
  isNonNegativeNumber,
  isRecord,
} from '@/utils/guards';

export class ProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProtocolError';
  }
}

function stringField(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== 'string' || value.length === 0) {
    throw new ProtocolError(`响应字段 ${key} 无效`);
  }
  return value;
}

function coordinateSystem(value: unknown): CoordinateSystem {
  if (value === 'WGS84' || value === 'GCJ02') return value;
  throw new ProtocolError('响应坐标系未知');
}

function outputCoordinate(value: unknown, field: string) {
  if (!isRecord(value) || !isFiniteNumber(value.lng) || !isFiniteNumber(value.lat)) {
    throw new ProtocolError(`${field}坐标无效`);
  }
  return { lng: value.lng, lat: value.lat };
}

function planningMode(value: unknown): RoutePlanningMode {
  if (
    value === 'INTERNAL_SAFE' ||
    value === 'CROSS_BOUNDARY_OUTBOUND' ||
    value === 'CROSS_BOUNDARY_INBOUND' ||
    value === 'EXTERNAL_ONLY'
  ) {
    return value;
  }
  throw new ProtocolError('路线规划模式无效');
}

function boundaryDirection(value: unknown): BoundaryDirection {
  if (value === 'OUTBOUND' || value === 'INBOUND') return value;
  throw new ProtocolError('六环通行方向无效');
}

function boundaryRole(value: unknown): BoundaryRole {
  if (value === 'OUTER_EXIT' || value === 'INNER_ENTRY') return value;
  throw new ProtocolError('六环边界角色无效');
}

function requiredNullable(record: Record<string, unknown>, key: string): unknown {
  if (!Object.prototype.hasOwnProperty.call(record, key)) {
    throw new ProtocolError(`响应缺少字段 ${key}`);
  }
  return record[key];
}

function parseSegment(value: unknown, field: string): RouteSegment {
  if (!isRecord(value)) throw new ProtocolError(`${field}结构无效`);
  if (
    !isNonNegativeNumber(value.distanceMeters) ||
    !isNonNegativeInteger(value.durationSeconds) ||
    !Array.isArray(value.geometry) ||
    value.geometry.length < 2
  ) {
    throw new ProtocolError(`${field}距离、时间或几何无效`);
  }
  return {
    distanceMeters: value.distanceMeters,
    durationSeconds: value.durationSeconds,
    geometry: value.geometry.map((point) => outputCoordinate(point, field)),
  };
}

function parseBoundaryCrossing(value: unknown): BoundaryCrossing {
  if (!isRecord(value)) throw new ProtocolError('六环通行口结构无效');
  const roadName = value.roadName;
  if (roadName !== undefined && roadName !== null && typeof roadName !== 'string') {
    throw new ProtocolError('六环通行口道路名称无效');
  }
  return {
    portalId: stringField(value, 'portalId'),
    ...(roadName !== undefined ? { roadName } : {}),
    direction: boundaryDirection(value.direction),
    boundaryRole: boundaryRole(value.boundaryRole),
    wgs84: outputCoordinate(value.wgs84, '六环通行口 WGS84'),
    gcj02: outputCoordinate(value.gcj02, '六环通行口 GCJ02'),
  };
}

function parseExternalHandoff(
  value: unknown,
  handoffType: NavigationHandoff['type'],
): ExternalHandoff {
  if (!isRecord(value)) throw new ProtocolError('环外交接位置结构无效');
  const expectedPoiRadius =
    handoffType === 'HIGHWAY'
      ? 0
      : isNonNegativeNumber(value.boundaryClearanceMeters)
        ? Math.floor(Math.min(200, Math.max(0, value.boundaryClearanceMeters - 25)))
        : -1;
  if (
    !isNonNegativeNumber(value.boundaryClearanceMeters) ||
    !isNonNegativeInteger(value.poiSearchRadiusMeters) ||
    value.poiSearchRadiusMeters !== expectedPoiRadius
  ) {
    throw new ProtocolError('环外交接位置边界距离或搜索半径无效');
  }
  return {
    wgs84: outputCoordinate(value.wgs84, '环外交接位置 WGS84'),
    gcj02: outputCoordinate(value.gcj02, '环外交接位置 GCJ02'),
    boundaryClearanceMeters: value.boundaryClearanceMeters,
    poiSearchRadiusMeters: value.poiSearchRadiusMeters,
  };
}

function parseNavigationHandoff(value: unknown): NavigationHandoff {
  if (!isRecord(value)) throw new ProtocolError('导航交接点结构无效');
  if (!isNonNegativeNumber(value.boundaryClearanceMeters) || value.boundaryClearanceMeters <= 0) {
    throw new ProtocolError('导航交接点边界距离无效');
  }
  const roadName = value.roadName;
  if (roadName !== undefined && roadName !== null && typeof roadName !== 'string') {
    throw new ProtocolError('导航交接点道路名称无效');
  }
  if (value.type !== 'ORDINARY_ROAD' && value.type !== 'HIGHWAY') {
    throw new ProtocolError('导航交接点类型无效');
  }
  return {
    wgs84: outputCoordinate(value.wgs84, '导航交接点 WGS84'),
    gcj02: outputCoordinate(value.gcj02, '导航交接点 GCJ02'),
    boundaryClearanceMeters: value.boundaryClearanceMeters,
    ...(roadName !== undefined ? { roadName } : {}),
    type: value.type,
  };
}

function parseStep(value: unknown): RouteStep {
  if (!isRecord(value)) throw new ProtocolError('路线步骤结构无效');
  const distanceMeters = value.distanceMeters;
  const durationSeconds = value.durationSeconds;
  const startIndex = value.startIndex;
  const endIndex = value.endIndex;
  if (
    !isNonNegativeNumber(distanceMeters) ||
    !isNonNegativeInteger(durationSeconds) ||
    !isNonNegativeInteger(startIndex) ||
    !isNonNegativeInteger(endIndex)
  ) {
    throw new ProtocolError('路线步骤数值无效');
  }
  const roadName = value.roadName;
  if (roadName !== undefined && roadName !== null && typeof roadName !== 'string') {
    throw new ProtocolError('路线道路名称无效');
  }
  return {
    instruction: stringField(value, 'instruction'),
    ...(roadName !== undefined ? { roadName } : {}),
    distanceMeters,
    durationSeconds,
    startIndex,
    endIndex,
  };
}

export function parseRouteResponse(value: unknown): RouteResponse {
  if (!isRecord(value)) throw new ProtocolError('路线响应结构无效');
  const conflictCount = value.cameraConflictCount;
  if (conflictCount !== 0) {
    throw new ProtocolError('路线响应包含摄像头冲突，已拒绝绘制');
  }
  if (!Array.isArray(value.geometry) || value.geometry.length < 2) {
    throw new ProtocolError('路线几何点不足');
  }
  const geometry = value.geometry.map((point) => outputCoordinate(point, '路线几何'));
  if (!Array.isArray(value.steps)) throw new ProtocolError('路线步骤结构无效');
  const distanceMeters = value.distanceMeters;
  const durationSeconds = value.durationSeconds;
  if (!isNonNegativeNumber(distanceMeters) || !isNonNegativeInteger(durationSeconds)) {
    throw new ProtocolError('路线距离或时间无效');
  }
  const mode = planningMode(value.planningMode);
  const directionValue = requiredNullable(value, 'boundaryDirection');
  const crossingValue = requiredNullable(value, 'boundaryCrossing');
  const navigationHandoffValue = requiredNullable(value, 'navigationHandoff');
  const externalHandoffValue = requiredNullable(value, 'externalHandoff');
  const safeValue = requiredNullable(value, 'safeSegment');
  const referenceValue = requiredNullable(value, 'referenceSegment');
  const direction = directionValue === null ? null : boundaryDirection(directionValue);
  const crossing = crossingValue === null ? null : parseBoundaryCrossing(crossingValue);
  const navigationHandoff =
    navigationHandoffValue === null ? null : parseNavigationHandoff(navigationHandoffValue);
  const externalHandoff =
    externalHandoffValue === null
      ? null
      : parseExternalHandoff(externalHandoffValue, navigationHandoff?.type ?? 'ORDINARY_ROAD');
  const safeSegment = safeValue === null ? null : parseSegment(safeValue, '环内安全段');
  const referenceSegment =
    referenceValue === null ? null : parseSegment(referenceValue, '环外参考段');

  const internalShape =
    mode === 'INTERNAL_SAFE' &&
    direction === null &&
    crossing === null &&
    navigationHandoff === null &&
    externalHandoff === null &&
    safeSegment !== null &&
    referenceSegment === null;
  const externalShape =
    mode === 'EXTERNAL_ONLY' &&
    direction === null &&
    crossing === null &&
    navigationHandoff === null &&
    externalHandoff === null &&
    safeSegment === null &&
    referenceSegment !== null;
  const expectedDirection =
    mode === 'CROSS_BOUNDARY_OUTBOUND'
      ? 'OUTBOUND'
      : mode === 'CROSS_BOUNDARY_INBOUND'
        ? 'INBOUND'
        : null;
  const expectedRole =
    mode === 'CROSS_BOUNDARY_OUTBOUND'
      ? 'OUTER_EXIT'
      : mode === 'CROSS_BOUNDARY_INBOUND'
        ? 'INNER_ENTRY'
        : null;
  const navigationJoinMatches =
    navigationHandoff === null ||
    (expectedDirection === 'OUTBOUND'
      ? safeSegment?.geometry.at(-1)?.lng === navigationHandoff.gcj02.lng &&
        safeSegment?.geometry.at(-1)?.lat === navigationHandoff.gcj02.lat &&
        referenceSegment?.geometry[0]?.lng === navigationHandoff.gcj02.lng &&
        referenceSegment?.geometry[0]?.lat === navigationHandoff.gcj02.lat
      : expectedDirection === 'INBOUND' &&
        safeSegment?.geometry[0]?.lng === navigationHandoff.gcj02.lng &&
        safeSegment?.geometry[0]?.lat === navigationHandoff.gcj02.lat &&
        referenceSegment?.geometry.at(-1)?.lng === navigationHandoff.gcj02.lng &&
        referenceSegment?.geometry.at(-1)?.lat === navigationHandoff.gcj02.lat);
  const handoffPairMatches = (navigationHandoff === null) === (externalHandoff === null);
  const handoffCoordinatesMatch =
    navigationHandoff === null ||
    (externalHandoff !== null &&
      navigationHandoff.wgs84.lng === externalHandoff.wgs84.lng &&
      navigationHandoff.wgs84.lat === externalHandoff.wgs84.lat &&
      navigationHandoff.gcj02.lng === externalHandoff.gcj02.lng &&
      navigationHandoff.gcj02.lat === externalHandoff.gcj02.lat);
  const handoffTypeMatches =
    navigationHandoff === null ||
    navigationHandoff.type === 'ORDINARY_ROAD' ||
    expectedDirection === 'INBOUND';
  const crossBoundaryShape =
    expectedDirection !== null &&
    direction === expectedDirection &&
    crossing?.direction === expectedDirection &&
    crossing?.boundaryRole === expectedRole &&
    safeSegment !== null &&
    referenceSegment !== null &&
    handoffPairMatches &&
    handoffCoordinatesMatch &&
    handoffTypeMatches &&
    navigationJoinMatches;
  if (!internalShape && !externalShape && !crossBoundaryShape) {
    throw new ProtocolError('路线规划模式与分段结构不一致');
  }
  return {
    routeId: stringField(value, 'routeId'),
    coordinateSystem: coordinateSystem(value.coordinateSystem),
    planningMode: mode,
    boundaryVersion: stringField(value, 'boundaryVersion'),
    boundaryDirection: direction,
    boundaryCrossing: crossing,
    navigationHandoff,
    externalHandoff,
    safeSegment,
    referenceSegment,
    distanceMeters,
    durationSeconds,
    cameraConflictCount: 0,
    cameraSnapshotVersion: stringField(value, 'cameraSnapshotVersion'),
    blockedEdgeVersion: stringField(value, 'blockedEdgeVersion'),
    geometry,
    steps: value.steps.map(parseStep),
  };
}

export function parseCameraPage(value: unknown): CameraPage {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    throw new ProtocolError('摄像头响应结构无效');
  }
  const items = value.items.map((item) => {
    if (!isRecord(item) || !isFiniteNumber(item.lng) || !isFiniteNumber(item.lat)) {
      throw new ProtocolError('摄像头坐标无效');
    }
    const directionText = item.directionText;
    if (
      directionText !== undefined &&
      directionText !== null &&
      typeof directionText !== 'string'
    ) {
      throw new ProtocolError('摄像头方向字段无效');
    }
    return {
      id: stringField(item, 'id'),
      lng: item.lng,
      lat: item.lat,
      address: stringField(item, 'address'),
      cameraType: stringField(item, 'cameraType'),
      ...(directionText !== undefined ? { directionText } : {}),
    };
  });
  return {
    coordinateSystem: coordinateSystem(value.coordinateSystem),
    snapshotVersion: stringField(value, 'snapshotVersion'),
    items,
  };
}

export function parseCameraSnapshotStatus(value: unknown): CameraSnapshotStatus {
  if (!isRecord(value) || value.status !== 'READY') {
    throw new ProtocolError('摄像头快照响应无效');
  }
  if (
    !isNonNegativeInteger(value.cameraCount) ||
    !isNonNegativeInteger(value.sourceCameraCount) ||
    !isNonNegativeInteger(value.outsideControlAreaCameraCount) ||
    value.cameraCount + value.outsideControlAreaCameraCount !== value.sourceCameraCount ||
    !isNonNegativeNumber(value.cameraOutsideMarginMeters) ||
    !isNonNegativeNumber(value.safetyRadiusMeters)
  ) {
    throw new ProtocolError('摄像头快照统计无效');
  }
  return {
    status: 'READY',
    snapshotVersion: stringField(value, 'snapshotVersion'),
    blockedEdgeVersion: stringField(value, 'blockedEdgeVersion'),
    cameraCount: value.cameraCount,
    sourceCameraCount: value.sourceCameraCount,
    outsideControlAreaCameraCount: value.outsideControlAreaCameraCount,
    cameraOutsideMarginMeters: value.cameraOutsideMarginMeters,
    controlBoundaryVersion: stringField(value, 'controlBoundaryVersion'),
    safetyRadiusMeters: value.safetyRadiusMeters,
    loadedAt: stringField(value, 'loadedAt'),
  };
}

export function parseControlledArea(value: unknown): ControlledArea {
  if (
    !isRecord(value) ||
    value.coordinateSystem !== 'GCJ02' ||
    typeof value.approvedForProduction !== 'boolean' ||
    !isNonNegativeNumber(value.cameraOutsideMarginMeters) ||
    !isRecord(value.geometry) ||
    value.geometry.type !== 'MultiPolygon' ||
    !Array.isArray(value.geometry.coordinates) ||
    value.geometry.coordinates.length === 0
  ) {
    throw new ProtocolError('受控区响应无效');
  }
  const coordinates = value.geometry.coordinates.map((polygon, polygonIndex) => {
    if (!Array.isArray(polygon) || polygon.length === 0) {
      throw new ProtocolError(`受控区多边形 ${polygonIndex} 无效`);
    }
    return polygon.map((ring, ringIndex) => {
      if (!Array.isArray(ring) || ring.length < 4) {
        throw new ProtocolError(`受控区边界环 ${polygonIndex}:${ringIndex} 无效`);
      }
      const parsed = ring.map((point) => outputCoordinate(point, '受控区'));
      const first = parsed[0];
      const last = parsed.at(-1);
      if (!first || !last || first.lng !== last.lng || first.lat !== last.lat) {
        throw new ProtocolError(`受控区边界环 ${polygonIndex}:${ringIndex} 未闭合`);
      }
      return parsed;
    });
  });
  return {
    boundaryVersion: stringField(value, 'boundaryVersion'),
    coordinateSystem: 'GCJ02',
    geometry: { type: 'MultiPolygon', coordinates },
    approvedForProduction: value.approvedForProduction,
    cameraOutsideMarginMeters: value.cameraOutsideMarginMeters,
  };
}

export function parseHealthResponse(value: unknown): HealthResponse {
  if (!isRecord(value) || value.status !== 'UP') throw new ProtocolError('健康检查响应无效');
  return { status: 'UP' };
}

export function parseReadinessResponse(value: unknown): ReadinessResponse {
  if (!isRecord(value) || (value.status !== 'READY' && value.status !== 'NOT_READY')) {
    throw new ProtocolError('就绪检查响应无效');
  }
  if (
    typeof value.graphLoaded !== 'boolean' ||
    typeof value.sixthRingTopologyLoaded !== 'boolean' ||
    typeof value.cameraSnapshotLoaded !== 'boolean' ||
    typeof value.blockedEdgesLoaded !== 'boolean'
  ) {
    throw new ProtocolError('就绪检查字段无效');
  }
  const reason = value.reason;
  if (reason !== undefined && reason !== null && typeof reason !== 'string') {
    throw new ProtocolError('就绪检查原因无效');
  }
  return {
    status: value.status,
    graphLoaded: value.graphLoaded,
    sixthRingTopologyLoaded: value.sixthRingTopologyLoaded,
    cameraSnapshotLoaded: value.cameraSnapshotLoaded,
    blockedEdgesLoaded: value.blockedEdgesLoaded,
    ...(reason !== undefined ? { reason } : {}),
  };
}

export function parseApiError(value: unknown): ApiError | null {
  if (!isRecord(value)) return null;
  if (!API_ERROR_CODES.includes(value.code as (typeof API_ERROR_CODES)[number])) return null;
  if (
    typeof value.message !== 'string' ||
    typeof value.requestId !== 'string' ||
    typeof value.timestamp !== 'string'
  ) {
    return null;
  }
  return {
    code: value.code as ApiError['code'],
    message: value.message,
    requestId: value.requestId,
    timestamp: value.timestamp,
    ...(isRecord(value.details) ? { details: value.details } : {}),
  };
}
