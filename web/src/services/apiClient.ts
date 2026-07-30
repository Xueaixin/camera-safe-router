import type {
  BoundingBox,
  CameraPage,
  CameraSnapshotStatus,
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
  health(signal?: AbortSignal): Promise<HealthResponse>;
  readiness(signal?: AbortSignal): Promise<ReadinessResponse>;
}
