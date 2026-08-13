import {
  buildMockCrossBoundaryRoute,
  buildMockExternalOnlyRoute,
  buildMockRoute,
  mockApiError,
  MOCK_CAMERA_PAGE,
  MOCK_CONTROLLED_AREA,
  MOCK_SNAPSHOT,
} from './fixtures';
import type { ApiClient } from '@/services/apiClient';
import { ApiClientError } from '@/services/errors';
import {
  parseCameraPage,
  parseCameraSnapshotStatus,
  parseControlledArea,
  parseRouteResponse,
} from '@/services/protocol';
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

export type MockScenario =
  | 'success'
  | 'no-route'
  | 'start-restricted'
  | 'end-restricted'
  | 'outside-bounds'
  | 'not-ready'
  | 'snapshot-not-ready'
  | 'network-error'
  | 'protocol-conflict'
  | 'invalid-geometry'
  | 'cross-boundary'
  | 'external-only'
  | 'reroute-failure';

export interface MockApiOptions {
  scenario?: MockScenario;
  delayMs?: number;
}

function scenarioFromLocation(): MockScenario {
  const value = new URLSearchParams(window.location.search).get('mockScenario');
  const scenarios: MockScenario[] = [
    'success',
    'no-route',
    'start-restricted',
    'end-restricted',
    'outside-bounds',
    'not-ready',
    'snapshot-not-ready',
    'network-error',
    'protocol-conflict',
    'invalid-geometry',
    'cross-boundary',
    'external-only',
    'reroute-failure',
  ];
  return scenarios.includes(value as MockScenario) ? (value as MockScenario) : 'success';
}

function delayFromLocation(): number {
  const parsed = Number(new URLSearchParams(window.location.search).get('mockDelay'));
  return Number.isFinite(parsed) && parsed >= 0 ? Math.min(parsed, 5_000) : 350;
}

function wait(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('请求已取消', 'AbortError'));
      return;
    }
    const timer = window.setTimeout(resolve, ms);
    signal?.addEventListener(
      'abort',
      () => {
        window.clearTimeout(timer);
        reject(new DOMException('请求已取消', 'AbortError'));
      },
      { once: true },
    );
  });
}

function throwBusiness(status: number, code: Parameters<typeof mockApiError>[0]): never {
  throw new ApiClientError(status, mockApiError(code));
}

export class MockApiClient implements ApiClient {
  private readonly scenario: MockScenario;
  private readonly delayMs: number;
  private routeSequence = 0;

  constructor(options: MockApiOptions = {}) {
    this.scenario = options.scenario ?? scenarioFromLocation();
    this.delayMs = options.delayMs ?? delayFromLocation();
  }

  async planRoute(request: RouteRequest, signal?: AbortSignal): Promise<RouteResponse> {
    await wait(this.delayMs, signal);
    const isReroute = request.start.source === 'CURRENT_LOCATION';
    if (this.scenario === 'network-error') throw new TypeError('Mock network disconnected');
    if (this.scenario === 'reroute-failure' && isReroute) {
      throwBusiness(409, 'NO_COMPLIANT_ROUTE');
    }
    if (this.scenario === 'no-route') throwBusiness(409, 'NO_COMPLIANT_ROUTE');
    if (this.scenario === 'start-restricted') throwBusiness(409, 'START_IN_RESTRICTED_AREA');
    if (this.scenario === 'end-restricted') throwBusiness(409, 'END_IN_RESTRICTED_AREA');
    if (this.scenario === 'outside-bounds') throwBusiness(422, 'OUTSIDE_ROUTING_BOUNDS');
    if (this.scenario === 'not-ready') throwBusiness(503, 'ROUTING_NOT_READY');
    if (this.scenario === 'snapshot-not-ready') {
      throwBusiness(503, 'CAMERA_SNAPSHOT_NOT_READY');
    }

    const routeId = `route-fixture-${++this.routeSequence}`;
    const response =
      this.scenario === 'cross-boundary'
        ? buildMockCrossBoundaryRoute(request, routeId)
        : this.scenario === 'external-only'
          ? buildMockExternalOnlyRoute(request, routeId)
          : buildMockRoute(request, routeId);
    if (this.scenario === 'protocol-conflict') {
      return parseRouteResponse({ ...response, cameraConflictCount: 1 });
    }
    if (this.scenario === 'invalid-geometry') {
      return parseRouteResponse({ ...response, geometry: [{ lng: 116.397, lat: 39.908 }] });
    }
    return parseRouteResponse(response);
  }

  async listCameras(
    bbox: BoundingBox,
    coordinateSystem: CoordinateSystem,
    signal?: AbortSignal,
  ): Promise<CameraPage> {
    await wait(Math.min(this.delayMs, 250), signal);
    if (this.scenario === 'network-error') throw new TypeError('Mock network disconnected');
    const items = MOCK_CAMERA_PAGE.items.filter(
      (item) =>
        item.lng >= bbox.minLng &&
        item.lng <= bbox.maxLng &&
        item.lat >= bbox.minLat &&
        item.lat <= bbox.maxLat,
    );
    return parseCameraPage({ ...MOCK_CAMERA_PAGE, coordinateSystem, items });
  }

  async getCurrentCameraSnapshot(signal?: AbortSignal): Promise<CameraSnapshotStatus> {
    await wait(Math.min(this.delayMs, 150), signal);
    if (this.scenario === 'snapshot-not-ready') {
      throwBusiness(503, 'CAMERA_SNAPSHOT_NOT_READY');
    }
    return parseCameraSnapshotStatus(MOCK_SNAPSHOT);
  }

  async getControlledArea(signal?: AbortSignal): Promise<ControlledArea> {
    await wait(Math.min(this.delayMs, 150), signal);
    return parseControlledArea(MOCK_CONTROLLED_AREA);
  }

  async health(signal?: AbortSignal): Promise<HealthResponse> {
    await wait(20, signal);
    return { status: 'UP' };
  }

  async readiness(signal?: AbortSignal): Promise<ReadinessResponse> {
    await wait(20, signal);
    const ready = this.scenario !== 'not-ready' && this.scenario !== 'snapshot-not-ready';
    return {
      status: ready ? 'READY' : 'NOT_READY',
      graphLoaded: this.scenario !== 'not-ready',
      sixthRingTopologyLoaded: this.scenario !== 'not-ready',
      cameraSnapshotLoaded: this.scenario !== 'snapshot-not-ready',
      blockedEdgesLoaded: ready,
      ...(ready ? {} : { reason: 'Mock service not ready' }),
    };
  }
}
