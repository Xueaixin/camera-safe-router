import type { CoordinateSystem, InputCoordinate } from './coordinate';

export interface RouteRequest {
  start: InputCoordinate;
  end: InputCoordinate;
  vehicle: 'CAR';
}

export interface OutputCoordinate {
  lng: number;
  lat: number;
}

export interface RouteStep {
  instruction: string;
  roadName?: string | null;
  distanceMeters: number;
  durationSeconds: number;
  startIndex: number;
  endIndex: number;
}

export interface RouteResponse {
  routeId: string;
  coordinateSystem: CoordinateSystem;
  distanceMeters: number;
  durationSeconds: number;
  cameraConflictCount: 0;
  cameraSnapshotVersion: string;
  blockedEdgeVersion: string;
  geometry: OutputCoordinate[];
  steps: RouteStep[];
}

export interface CameraView {
  id: string;
  lng: number;
  lat: number;
  address: string;
  cameraType: string;
  directionText?: string | null;
}

export interface CameraPage {
  coordinateSystem: CoordinateSystem;
  snapshotVersion: string;
  items: CameraView[];
}

export interface CameraSnapshotStatus {
  status: 'READY';
  snapshotVersion: string;
  blockedEdgeVersion: string;
  cameraCount: number;
  safetyRadiusMeters: number;
  loadedAt: string;
}

export interface HealthResponse {
  status: 'UP';
}

export interface ReadinessResponse {
  status: 'READY' | 'NOT_READY';
  graphLoaded: boolean;
  cameraSnapshotLoaded: boolean;
  blockedEdgesLoaded: boolean;
  reason?: string | null;
}

export const API_ERROR_CODES = [
  'INVALID_REQUEST',
  'INVALID_COORDINATE',
  'UNSUPPORTED_COORDINATE_SYSTEM',
  'OUTSIDE_ROUTING_BOUNDS',
  'START_IN_RESTRICTED_AREA',
  'END_IN_RESTRICTED_AREA',
  'NO_COMPLIANT_ROUTE',
  'ROUTE_CONFLICT_DETECTED',
  'ROUTING_NOT_READY',
  'CAMERA_SNAPSHOT_NOT_READY',
  'REFRESH_ALREADY_RUNNING',
  'INTERNAL_ERROR',
] as const;

export type ApiErrorCode = (typeof API_ERROR_CODES)[number];

export interface ApiError {
  code: ApiErrorCode;
  message: string;
  requestId: string;
  timestamp: string;
  details?: Record<string, unknown>;
}

export interface BoundingBox {
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
}
