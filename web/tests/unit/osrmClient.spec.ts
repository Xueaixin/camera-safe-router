import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchOsrmRoutes } from '@/services/osrmClient';

describe('osrmClient', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parses backend geometry as [lng, lat] pairs and converts to GCJ02', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => ({
          code: 'Ok',
          routes: [
            {
              distanceMeters: 134_200,
              durationSeconds: 6018,
              geometry: [
                [116.5, 39.9],
                [116.6, 39.8],
                [117.07, 39.31],
              ],
            },
          ],
        }),
      })),
    );

    const routes = await fetchOsrmRoutes({ lng: 116.5, lat: 39.9 }, { lng: 117.07, lat: 39.31 });

    expect(routes).toHaveLength(1);
    const route = routes[0]!;
    expect(route.distanceMeters).toBe(134_200);
    expect(route.durationSeconds).toBe(6018);
    expect(route.geometry).toHaveLength(3);
    const first = route.geometry[0]!;
    const second = route.geometry[1]!;
    expect(typeof first.lng).toBe('number');
    expect(first.lng).not.toBe(116.5);
    expect(Number.isFinite(second.lat)).toBe(true);
  });

  it('rejects a non-Ok payload', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => ({ code: 'NoRoute', routes: [] }),
      })),
    );

    await expect(
      fetchOsrmRoutes({ lng: 116.5, lat: 39.9 }, { lng: 117.07, lat: 39.31 }),
    ).rejects.toThrow('界外路线返回异常');
  });
});
