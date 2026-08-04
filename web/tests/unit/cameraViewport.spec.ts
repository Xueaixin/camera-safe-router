import { describe, expect, it, vi } from 'vitest';

import { createCameraViewportController } from '@/composables/useCameraViewport';
import { ApiClientError } from '@/services/errors';
import type { ApiClient } from '@/services/apiClient';
import type { CameraPage } from '@/types/api';
import type { Coordinate, DisplayLocation, SelectedPlace } from '@/types/coordinate';
import type { MapAdapter, MapAdapterCallbacks, MapViewport, PlaceSuggestion } from '@/types/map';

class MapStub implements MapAdapter {
  viewport: MapViewport = {
    minLng: 116.2,
    minLat: 39.8,
    maxLng: 116.6,
    maxLat: 40.1,
    zoom: 11,
  };
  listener: (() => void) | null = null;
  async initialize(_container: HTMLElement, _callbacks: MapAdapterCallbacks) {}
  destroy() {}
  async searchPlaces(): Promise<PlaceSuggestion[]> {
    return [];
  }
  async convertWgs84ToGcj02(coordinate: Coordinate) {
    return { ...coordinate, coordinateSystem: 'GCJ02' as const };
  }
  setEndpointMarkers(_start: SelectedPlace | null, _end: SelectedPlace | null) {}
  setRoute() {}
  clearRoute() {}
  fitRoute() {}
  setCameras() {}
  clearCameras() {}
  setCurrentLocation(_location: DisplayLocation | null) {}
  centerOn() {}
  getViewport() {
    return this.viewport;
  }
  subscribeViewport(listener: () => void) {
    this.listener = listener;
    return () => {
      this.listener = null;
    };
  }
}

function apiWithList(listCameras: ApiClient['listCameras']): ApiClient {
  return {
    listCameras,
    planRoute: async () => {
      throw new Error('unused');
    },
    getCurrentCameraSnapshot: async () => {
      throw new Error('unused');
    },
    health: async () => ({ status: 'UP' }),
    readiness: async () => ({
      status: 'READY',
      graphLoaded: true,
      sixthRingTopologyLoaded: true,
      cameraSnapshotLoaded: true,
      blockedEdgesLoaded: true,
    }),
  };
}

describe('camera viewport controller', () => {
  it('debounces viewport changes and prevents stale requests from replacing fresh data', async () => {
    vi.useFakeTimers();
    const map = new MapStub();
    const pending: Array<{
      signal: AbortSignal | undefined;
      resolve: (value: CameraPage) => void;
    }> = [];
    const api = apiWithList(
      (_bbox, _system, signal) => new Promise((resolve) => pending.push({ signal, resolve })),
    );
    const onData = vi.fn();
    const controller = createCameraViewportController(
      map,
      api,
      { onLoading: vi.fn(), onData, onError: vi.fn() },
      100,
    );
    controller.start();
    await vi.runOnlyPendingTimersAsync();
    expect(pending).toHaveLength(1);

    map.viewport = { ...map.viewport, minLng: 116.3, maxLng: 116.7 };
    map.listener?.();
    map.listener?.();
    await vi.runOnlyPendingTimersAsync();
    expect(pending).toHaveLength(2);
    expect(pending[0]?.signal?.aborted).toBe(true);

    pending[1]?.resolve({
      coordinateSystem: 'GCJ02',
      snapshotVersion: 'new',
      items: [{ id: 'new', lng: 116.4, lat: 39.9, address: '新', cameraType: '测试' }],
    });
    await Promise.resolve();
    pending[0]?.resolve({ coordinateSystem: 'GCJ02', snapshotVersion: 'old', items: [] });
    await Promise.resolve();

    expect(onData).toHaveBeenCalledTimes(1);
    expect(onData.mock.calls[0]?.[0][0]?.id).toBe('new');
    controller.stop();
    vi.useRealTimers();
  });

  it('shows the backend camera query error instead of replacing it with a generic message', async () => {
    vi.useFakeTimers();
    const map = new MapStub();
    const onError = vi.fn();
    const api = apiWithList(async () => {
      throw new ApiClientError(422, {
        code: 'CAMERA_QUERY_RESULT_LIMIT_EXCEEDED',
        message: '查询范围内点位超过服务返回上限，请放大地图后重试',
        requestId: 'request-1',
        timestamp: '2026-08-04T00:00:00Z',
      });
    });
    const controller = createCameraViewportController(
      map,
      api,
      { onLoading: vi.fn(), onData: vi.fn(), onError },
      100,
    );

    controller.start();
    await vi.runOnlyPendingTimersAsync();

    expect(onError).toHaveBeenLastCalledWith(
      '查询范围内点位超过服务返回上限，请放大地图后重试',
    );
    controller.stop();
    vi.useRealTimers();
  });
});
