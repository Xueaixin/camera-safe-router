import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadAmap } from '@/maps/amapLoader';
import { fetchAmapDrivingRoutes } from '@/maps/amapDriving';
import type { AmapDrivingResult, AmapLngLat, AmapNamespace } from '@/types/amap';

vi.mock('@/maps/amapLoader', () => ({ loadAmap: vi.fn() }));

const loadAmapMock = vi.mocked(loadAmap);

function lngLat(lng: number, lat: number): AmapLngLat {
  return { getLng: () => lng, getLat: () => lat };
}

type SearchCallback = (status: string, result: AmapDrivingResult) => void;

function installFakeDriving() {
  const callbacks: SearchCallback[] = [];
  const policies: (number | undefined)[] = [];
  class FakeDriving {
    constructor(options?: Record<string, unknown>) {
      policies.push(options?.policy as number | undefined);
    }

    search(_origin: [number, number], _destination: [number, number], callback: SearchCallback) {
      callbacks.push(callback);
    }
  }
  return { FakeDriving, callbacks, policies };
}

describe('amapDriving', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('parses driving routes from multiple policy searches', async () => {
    const { FakeDriving, callbacks, policies } = installFakeDriving();
    loadAmapMock.mockResolvedValue({
      Driving: FakeDriving,
      DrivingPolicy: { LEAST_TIME: 0, LEAST_FEE: 1, LEAST_DISTANCE: 2 },
    } as unknown as AmapNamespace);

    const promise = fetchAmapDrivingRoutes({ lng: 116.5, lat: 39.9 }, { lng: 117.07, lat: 39.31 });
    await vi.waitFor(() => {
      expect(callbacks).toHaveLength(3);
    });
    expect(policies).toEqual([0, 1, 2]);

    callbacks[0]!('complete', {
      routes: [
        {
          distance: 134_200,
          time: 6018,
          steps: [
            { path: [lngLat(116.5, 39.9), lngLat(116.6, 39.8)] },
            { path: [lngLat(116.8, 39.5), lngLat(117.07, 39.31)] },
          ],
        },
      ],
    });
    callbacks[1]!('complete', {
      routes: [
        {
          distance: 139_000,
          time: 6400,
          steps: [{ path: [lngLat(116.5, 39.9), lngLat(117.0, 39.2), lngLat(117.07, 39.31)] }],
        },
      ],
    });
    callbacks[2]!('complete', {
      routes: [
        {
          distance: 128_000,
          time: 6900,
          steps: [{ path: [lngLat(116.5, 39.9), lngLat(116.9, 39.4), lngLat(117.07, 39.31)] }],
        },
      ],
    });

    const routes = await promise;
    expect(routes).toHaveLength(3);
    const route = routes[0]!;
    expect(route.distanceMeters).toBe(134_200);
    expect(route.durationSeconds).toBe(6018);
    expect(route.geometry).toHaveLength(4);
    expect(route.geometry[0]!).toEqual({ lng: 116.5, lat: 39.9 });
    expect(route.geometry[3]!).toEqual({ lng: 117.07, lat: 39.31 });
  });

  it('dedupes routes shared across policies', async () => {
    const { FakeDriving, callbacks } = installFakeDriving();
    loadAmapMock.mockResolvedValue({
      Driving: FakeDriving,
      DrivingPolicy: { LEAST_TIME: 0, LEAST_FEE: 1, LEAST_DISTANCE: 2 },
    } as unknown as AmapNamespace);

    const promise = fetchAmapDrivingRoutes({ lng: 116.5, lat: 39.9 }, { lng: 117.07, lat: 39.31 });
    await vi.waitFor(() => {
      expect(callbacks).toHaveLength(3);
    });
    const sharedRoute = {
      distance: 134_200,
      time: 6018,
      steps: [{ path: [lngLat(116.5, 39.9), lngLat(117.07, 39.31)] }],
    };
    callbacks[0]!('complete', { routes: [sharedRoute] });
    callbacks[1]!('complete', { routes: [sharedRoute] });
    callbacks[2]!('complete', {
      routes: [
        {
          distance: 128_000,
          time: 6900,
          steps: [{ path: [lngLat(116.5, 39.9), lngLat(116.9, 39.4), lngLat(117.07, 39.31)] }],
        },
      ],
    });

    const routes = await promise;
    expect(routes).toHaveLength(2);
  });

  it('rejects when every policy search fails', async () => {
    const { FakeDriving, callbacks } = installFakeDriving();
    loadAmapMock.mockResolvedValue({
      Driving: FakeDriving,
      DrivingPolicy: { LEAST_TIME: 0, LEAST_FEE: 1, LEAST_DISTANCE: 2 },
    } as unknown as AmapNamespace);

    const promise = fetchAmapDrivingRoutes({ lng: 116.5, lat: 39.9 }, { lng: 117.07, lat: 39.31 });
    await vi.waitFor(() => {
      expect(callbacks).toHaveLength(3);
    });
    callbacks.forEach((callback) => callback('no_data', { routes: [] }));

    await expect(promise).rejects.toThrow('高德路线规划未返回可用路线');
  });
});
