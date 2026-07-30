import {
  API_ERROR_CODES,
  type ApiError,
  type CameraPage,
  type CameraSnapshotStatus,
  type HealthResponse,
  type ReadinessResponse,
  type RouteResponse,
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
  const geometry = value.geometry.map((point) => {
    if (!isRecord(point) || !isFiniteNumber(point.lng) || !isFiniteNumber(point.lat)) {
      throw new ProtocolError('路线几何坐标无效');
    }
    return { lng: point.lng, lat: point.lat };
  });
  if (!Array.isArray(value.steps)) throw new ProtocolError('路线步骤结构无效');
  const distanceMeters = value.distanceMeters;
  const durationSeconds = value.durationSeconds;
  if (!isNonNegativeNumber(distanceMeters) || !isNonNegativeInteger(durationSeconds)) {
    throw new ProtocolError('路线距离或时间无效');
  }
  return {
    routeId: stringField(value, 'routeId'),
    coordinateSystem: coordinateSystem(value.coordinateSystem),
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
  if (!isNonNegativeInteger(value.cameraCount) || !isNonNegativeNumber(value.safetyRadiusMeters)) {
    throw new ProtocolError('摄像头快照统计无效');
  }
  return {
    status: 'READY',
    snapshotVersion: stringField(value, 'snapshotVersion'),
    blockedEdgeVersion: stringField(value, 'blockedEdgeVersion'),
    cameraCount: value.cameraCount,
    safetyRadiusMeters: value.safetyRadiusMeters,
    loadedAt: stringField(value, 'loadedAt'),
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
