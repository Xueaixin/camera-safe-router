import { describe, expect, it } from 'vitest';

import { buildMockRoute } from '@/mocks/fixtures';
import { parseRouteResponse, ProtocolError } from '@/services/protocol';
import type { RouteRequest } from '@/types/api';

const request: RouteRequest = {
  start: { lng: 116.397, lat: 39.908, coordinateSystem: 'GCJ02', source: 'AMAP_SEARCH' },
  end: { lng: 116.47, lat: 39.992, coordinateSystem: 'GCJ02', source: 'AMAP_SEARCH' },
  vehicle: 'CAR',
};

describe('route response validation', () => {
  it('accepts the frozen success structure', () => {
    expect(parseRouteResponse(buildMockRoute(request)).cameraConflictCount).toBe(0);
  });

  it('rejects a successful response with any camera conflict', () => {
    expect(() =>
      parseRouteResponse({ ...buildMockRoute(request), cameraConflictCount: 1 }),
    ).toThrowError(ProtocolError);
  });

  it('rejects unknown coordinates and short geometry', () => {
    expect(() =>
      parseRouteResponse({ ...buildMockRoute(request), coordinateSystem: 'BD09' }),
    ).toThrow('响应坐标系未知');
    expect(() =>
      parseRouteResponse({ ...buildMockRoute(request), geometry: [{ lng: 116.397, lat: 39.908 }] }),
    ).toThrow('路线几何点不足');
  });
});
