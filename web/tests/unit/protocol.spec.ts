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
    const response = parseRouteResponse(buildMockRoute(request));
    expect(response.cameraConflictCount).toBe(0);
    expect(response.planningMode).toBe('INTERNAL_SAFE');
    expect(response.safeSegment?.geometry).toHaveLength(4);
    expect(response.referenceSegment).toBeNull();
  });

  it('accepts a complete outbound cross-boundary structure', () => {
    const base = buildMockRoute(request);
    const response = parseRouteResponse({
      ...base,
      planningMode: 'CROSS_BOUNDARY_OUTBOUND',
      boundaryDirection: 'OUTBOUND',
      boundaryCrossing: {
        portalId: 'P0001',
        roadName: '六环路',
        direction: 'OUTBOUND',
        boundaryRole: 'OUTER_EXIT',
        wgs84: { lng: 116.4, lat: 39.9 },
        gcj02: { lng: 116.406, lat: 39.901 },
      },
      navigationHandoff: {
        wgs84: { lng: 116.405, lat: 39.9 },
        gcj02: { lng: 116.411, lat: 39.901 },
        boundaryClearanceMeters: 320,
        roadName: '普通道路',
      },
      externalHandoff: {
        wgs84: { lng: 116.41, lat: 39.9 },
        gcj02: { lng: 116.416, lat: 39.901 },
        boundaryClearanceMeters: 260,
        poiSearchRadiusMeters: 200,
      },
      safeSegment: {
        distanceMeters: 4000,
        durationSeconds: 450,
        geometry: [base.geometry[0], { lng: 116.411, lat: 39.901 }],
      },
      referenceSegment: {
        distanceMeters: 8000,
        durationSeconds: 900,
        geometry: [{ lng: 116.411, lat: 39.901 }, base.geometry[base.geometry.length - 1]!],
      },
    });

    expect(response.boundaryCrossing?.portalId).toBe('P0001');
    expect(response.navigationHandoff?.roadName).toBe('普通道路');
    expect(response.externalHandoff?.poiSearchRadiusMeters).toBe(200);
  });

  it('requires an external handoff for cross-boundary routes', () => {
    const base = buildMockRoute(request);
    expect(() =>
      parseRouteResponse({
        ...base,
        planningMode: 'CROSS_BOUNDARY_OUTBOUND',
        boundaryDirection: 'OUTBOUND',
        boundaryCrossing: {
          portalId: 'P0001',
          direction: 'OUTBOUND',
          boundaryRole: 'OUTER_EXIT',
          wgs84: { lng: 116.4, lat: 39.9 },
          gcj02: { lng: 116.406, lat: 39.901 },
        },
        referenceSegment: base.safeSegment,
      }),
    ).toThrow('路线规划模式与分段结构不一致');
  });

  it('rejects missing nullable fields and mode/segment mismatches', () => {
    const base = buildMockRoute(request);
    const { boundaryCrossing: _boundaryCrossing, ...missingField } = base;
    expect(() => parseRouteResponse(missingField)).toThrow('响应缺少字段 boundaryCrossing');
    expect(() => parseRouteResponse({ ...base, planningMode: 'EXTERNAL_ONLY' })).toThrow(
      '路线规划模式与分段结构不一致',
    );
    expect(() =>
      parseRouteResponse({
        ...base,
        planningMode: 'CROSS_BOUNDARY_OUTBOUND',
        boundaryDirection: 'OUTBOUND',
        boundaryCrossing: {
          portalId: 'P0001',
          direction: 'OUTBOUND',
          boundaryRole: 'INNER_ENTRY',
          wgs84: { lng: 116.4, lat: 39.9 },
          gcj02: { lng: 116.406, lat: 39.901 },
        },
        referenceSegment: base.safeSegment,
      }),
    ).toThrow('路线规划模式与分段结构不一致');
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
