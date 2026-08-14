import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  buildMockCrossBoundaryRoute,
  buildMockExternalOnlyRoute,
  buildMockRoute,
  mockApiError,
  MOCK_CONTROLLED_AREA,
} from '@/mocks/fixtures';
import type { ApiClient } from '@/services/apiClient';
import { ApiClientError } from '@/services/errors';
import { fetchAmapDrivingRoutes } from '@/maps/amapDriving';
import { useRouteStore } from '@/stores/routeStore';
import type {
  BoundingBox,
  CameraPage,
  CameraSnapshotStatus,
  HealthResponse,
  ReadinessResponse,
  RouteRequest,
  RouteResponse,
} from '@/types/api';
import type { BrowserLocation, CoordinateSystem, SelectedPlace } from '@/types/coordinate';

vi.mock('@/maps/amapDriving', () => ({
  fetchAmapDrivingRoutes: vi.fn(),
}));

const drivingMock = vi.mocked(fetchAmapDrivingRoutes);

function place(name: string, lng: number, lat: number): SelectedPlace {
  return {
    name,
    coordinate: { lng, lat, coordinateSystem: 'GCJ02' },
    source: 'AMAP_SEARCH',
  };
}

function clientWith(
  planRoute: (request: RouteRequest, signal?: AbortSignal) => Promise<RouteResponse>,
): ApiClient {
  return {
    planRoute,
    listCameras: async (
      _bbox: BoundingBox,
      _coordinateSystem: CoordinateSystem,
    ): Promise<CameraPage> => ({
      coordinateSystem: 'GCJ02',
      snapshotVersion: 'fixture-camera-v1',
      items: [],
    }),
    getCurrentCameraSnapshot: async (): Promise<CameraSnapshotStatus> => ({
      status: 'READY',
      snapshotVersion: 'fixture-camera-v1',
      blockedEdgeVersion: 'fixture-blocked-v1',
      cameraCount: 0,
      sourceCameraCount: 0,
      outsideControlAreaCameraCount: 0,
      cameraOutsideMarginMeters: 50,
      controlBoundaryVersion: 'fixture-controlled-area-v1',
      safetyRadiusMeters: 30,
      loadedAt: '2026-07-30T00:00:00Z',
    }),
    getControlledArea: async () => MOCK_CONTROLLED_AREA,
    health: async (): Promise<HealthResponse> => ({ status: 'UP' }),
    readiness: async (): Promise<ReadinessResponse> => ({
      status: 'READY',
      graphLoaded: true,
      sixthRingTopologyLoaded: true,
      cameraSnapshotLoaded: true,
      blockedEdgesLoaded: true,
    }),
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe('route store', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('swaps endpoints and clears an obsolete route result', async () => {
    const store = useRouteStore();
    const start = place('起点', 116.397, 39.908);
    const end = place('终点', 116.47, 39.992);
    store.setStart(start);
    store.setEnd(end);
    await store.plan(clientWith(async (request) => buildMockRoute(request)));
    expect(store.route).not.toBeNull();

    store.swapEndpoints();
    expect(store.start?.name).toBe('终点');
    expect(store.end?.name).toBe('起点');
    expect(store.route).toBeNull();
  });

  it('reroutes with the clicked latest WGS84 location and preserves the original end', async () => {
    const store = useRouteStore();
    store.setStart(place('原起点', 116.397, 39.908));
    const originalEnd = place('原终点', 116.47, 39.992);
    store.setEnd(originalEnd);
    const planRoute = vi.fn(async (request: RouteRequest) => buildMockRoute(request));
    const latest: BrowserLocation = {
      coordinate: { lng: 116.39, lat: 39.9, coordinateSystem: 'WGS84' },
      accuracyMeters: 18.2,
      heading: null,
      speed: null,
      timestamp: new Date().toISOString(),
    };

    await store.rerouteFromLocation(latest, clientWith(planRoute));

    expect(planRoute).toHaveBeenCalledWith(
      expect.objectContaining({
        start: expect.objectContaining({
          lng: 116.39,
          coordinateSystem: 'WGS84',
          source: 'CURRENT_LOCATION',
        }),
        end: expect.objectContaining({ lng: 116.47, source: 'AMAP_SEARCH' }),
      }),
      expect.any(AbortSignal),
    );
    expect(store.start?.source).toBe('CURRENT_LOCATION');
    expect(store.end).toEqual(originalEnd);
  });

  it('keeps the old route and endpoints when manual rerouting fails', async () => {
    const store = useRouteStore();
    const oldStart = place('原起点', 116.397, 39.908);
    const end = place('终点', 116.47, 39.992);
    store.setStart(oldStart);
    store.setEnd(end);
    await store.plan(clientWith(async (request) => buildMockRoute(request, 'old-route')));
    const oldRoute = store.route;
    const location: BrowserLocation = {
      coordinate: { lng: 116.39, lat: 39.9, coordinateSystem: 'WGS84' },
      accuracyMeters: 20,
      heading: null,
      speed: null,
      timestamp: new Date().toISOString(),
    };
    const failing = clientWith(async () => {
      throw new ApiClientError(409, mockApiError('NO_COMPLIANT_ROUTE'));
    });

    await store.rerouteFromLocation(location, failing);

    expect(store.route).toBe(oldRoute);
    expect(store.start).toEqual(oldStart);
    expect(store.end).toEqual(end);
    expect(store.state).toBe('no-route');
  });

  it('does not let a late obsolete response overwrite the newer request', async () => {
    const store = useRouteStore();
    const first = deferred<RouteResponse>();
    const second = deferred<RouteResponse>();
    const requests: RouteRequest[] = [];
    const api = clientWith((request) => {
      requests.push(request);
      return requests.length === 1 ? first.promise : second.promise;
    });
    store.setStart(place('起点 A', 116.397, 39.908));
    store.setEnd(place('终点', 116.47, 39.992));
    const firstPlan = store.plan(api);
    store.setStart(place('起点 B', 116.41, 39.91));
    const secondPlan = store.plan(api);
    const secondRequest = requests[1];
    const firstRequest = requests[0];
    expect(secondRequest).toBeDefined();
    expect(firstRequest).toBeDefined();
    second.resolve(buildMockRoute(secondRequest!, 'new-route'));
    await secondPlan;
    first.resolve(buildMockRoute(firstRequest!, 'late-route'));
    await firstPlan;

    expect(store.route?.routeId).toBe('new-route');
  });

  it('switches a cross-boundary route between the full trip and safe segment', async () => {
    const store = useRouteStore();
    drivingMock.mockResolvedValue([
      {
        id: 'amap-0',
        distanceMeters: 100_000,
        durationSeconds: 3600,
        geometry: [
          { lng: 117.0, lat: 39.3 },
          { lng: 117.1, lat: 39.2 },
        ],
      },
    ]);
    store.setStart(place('环内起点', 116.397, 39.908));
    store.setEnd(place('环外终点', 117.21, 39.136));
    await store.plan(clientWith(async (request) => buildMockCrossBoundaryRoute(request)));
    await vi.waitFor(() => {
      expect(store.externalRoutes).toHaveLength(1);
    });

    expect(store.routeView).toBe('full');
    expect(store.displayGeometry).toHaveLength(4);
    expect(store.displayDistanceMeters).toBe(129200);

    store.showSafeSegment();
    expect(store.routeView).toBe('safe-segment');
    expect(store.displayGeometry).toHaveLength(4);
    expect(store.displayDistanceMeters).toBe(29200);

    store.showFullRoute();
    expect(store.routeView).toBe('full');
    store.setEnd(place('新终点', 116.47, 39.992));
    expect(store.routeView).toBe('full');
    expect(store.route).toBeNull();
  });

  it('loads OSRM external routes after a cross-boundary route succeeds', async () => {
    const store = useRouteStore();
    drivingMock.mockResolvedValue([
      {
        id: 'amap-0',
        distanceMeters: 100_000,
        durationSeconds: 3600,
        geometry: [
          { lng: 117.0, lat: 39.3 },
          { lng: 117.1, lat: 39.2 },
        ],
      },
      {
        id: 'amap-1',
        distanceMeters: 110_000,
        durationSeconds: 3900,
        geometry: [
          { lng: 117.0, lat: 39.3 },
          { lng: 117.2, lat: 39.1 },
        ],
      },
    ]);
    store.setStart(place('环内起点', 116.397, 39.908));
    store.setEnd(place('环外终点', 117.21, 39.136));
    await store.plan(clientWith(async (request) => buildMockCrossBoundaryRoute(request)));
    await vi.waitFor(() => {
      expect(store.externalRoutes).toHaveLength(2);
    });
    expect(store.selectedExternalRoute).toBe(0);
    expect(store.externalRouteState).toBe('idle');
    expect(store.displayDistanceMeters).toBe(129200);
    store.selectExternalRoute(1);
    expect(store.selectedExternalRoute).toBe(1);
    expect(store.displayDistanceMeters).toBe(139200);
    expect(drivingMock).toHaveBeenCalledOnce();
  });

  it('keeps an external-only route local without calling AMap', async () => {
    const store = useRouteStore();
    store.setStart(place('界外起点', 116.13, 40.075));
    store.setEnd(place('界外终点', 117.07, 39.31));
    await store.plan(clientWith(async (request) => buildMockExternalOnlyRoute(request)));

    expect(store.route?.planningMode).toBe('EXTERNAL_ONLY');
    expect(store.externalRoutes).toHaveLength(0);
    expect(store.externalRouteState).toBe('idle');
    expect(drivingMock).not.toHaveBeenCalled();
    expect(store.displayDistanceMeters).toBe(128_000);
    expect(store.displayGeometry).toHaveLength(4);
  });
});
