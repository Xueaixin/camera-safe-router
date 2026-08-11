import type {
  BoundingBox,
  CameraPage,
  CameraSnapshotStatus,
  ControlledArea,
  HealthResponse,
  ReadinessResponse,
  RouteRequest,
  RouteResponse,
} from '@/types/api';
import type { CoordinateSystem } from '@/types/coordinate';

export interface ApiClient {
  planRoute(request: RouteRequest, signal?: AbortSignal): Promise<RouteResponse>;
  listCameras(
    bbox: BoundingBox,
    coordinateSystem: CoordinateSystem,
    signal?: AbortSignal,
  ): Promise<CameraPage>;
  getCurrentCameraSnapshot(signal?: AbortSignal): Promise<CameraSnapshotStatus>;
  getControlledArea(signal?: AbortSignal): Promise<ControlledArea>;
  health(signal?: AbortSignal): Promise<HealthResponse>;
  readiness(signal?: AbortSignal): Promise<ReadinessResponse>;
}
