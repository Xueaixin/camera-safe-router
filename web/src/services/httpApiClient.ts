import { ApiClientError } from './errors';
import {
  parseApiError,
  parseCameraPage,
  parseCameraSnapshotStatus,
  parseHealthResponse,
  parseReadinessResponse,
  parseRouteResponse,
} from './protocol';
import type { ApiClient } from './apiClient';
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

const REQUEST_TIMEOUT_MS = 15_000;

function composeSignal(external?: AbortSignal): { signal: AbortSignal; cleanup: () => void } {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort('timeout'), REQUEST_TIMEOUT_MS);
  const abort = () => controller.abort(external?.reason);
  external?.addEventListener('abort', abort, { once: true });
  return {
    signal: controller.signal,
    cleanup: () => {
      window.clearTimeout(timeout);
      external?.removeEventListener('abort', abort);
    },
  };
}

export class HttpApiClient implements ApiClient {
  constructor(private readonly baseUrl: string) {}

  async planRoute(request: RouteRequest, signal?: AbortSignal): Promise<RouteResponse> {
    const value = await this.request('/api/v1/routes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(request),
      signal,
    });
    return parseRouteResponse(value);
  }

  async listCameras(
    bbox: BoundingBox,
    coordinateSystem: CoordinateSystem,
    signal?: AbortSignal,
  ): Promise<CameraPage> {
    const query = new URLSearchParams({
      minLng: String(bbox.minLng),
      minLat: String(bbox.minLat),
      maxLng: String(bbox.maxLng),
      maxLat: String(bbox.maxLat),
      coordinateSystem,
    });
    return parseCameraPage(await this.request(`/api/v1/cameras?${query.toString()}`, { signal }));
  }

  async getCurrentCameraSnapshot(signal?: AbortSignal): Promise<CameraSnapshotStatus> {
    return parseCameraSnapshotStatus(
      await this.request('/api/v1/camera-snapshots/current', { signal }),
    );
  }

  async health(signal?: AbortSignal): Promise<HealthResponse> {
    return parseHealthResponse(await this.request('/api/v1/health', { signal }));
  }

  async readiness(signal?: AbortSignal): Promise<ReadinessResponse> {
    return parseReadinessResponse(await this.request('/api/v1/readiness', { signal }, [503]));
  }

  private async request(
    path: string,
    init: Omit<RequestInit, 'signal'> & { signal?: AbortSignal | undefined },
    acceptedStatuses: readonly number[] = [],
  ) {
    const composed = composeSignal(init.signal);
    try {
      const response = await fetch(`${this.baseUrl}${path}`, { ...init, signal: composed.signal });
      let body: unknown;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      if (!response.ok && !acceptedStatuses.includes(response.status)) {
        const parsed = parseApiError(body) ?? {
          code: 'INTERNAL_ERROR' as const,
          message: '服务返回了无法识别的错误',
          requestId: 'unavailable',
          timestamp: new Date().toISOString(),
        };
        throw new ApiClientError(response.status, parsed);
      }
      return body;
    } finally {
      composed.cleanup();
    }
  }
}
