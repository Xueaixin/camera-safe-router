import { describe, expect, it } from 'vitest';

import { isPointInsideControlledArea } from '@/utils/controlledArea';
import type { ControlledArea } from '@/types/api';

function rectangleArea(): ControlledArea {
  return {
    boundaryVersion: 'fixture-controlled-area-v1',
    coordinateSystem: 'GCJ02',
    geometry: {
      type: 'MultiPolygon',
      coordinates: [
        [
          [
            { lng: 116.29, lat: 39.84 },
            { lng: 116.53, lat: 39.84 },
            { lng: 116.53, lat: 40.02 },
            { lng: 116.29, lat: 40.02 },
            { lng: 116.29, lat: 39.84 },
          ],
        ],
      ],
    },
    approvedForProduction: false,
    cameraOutsideMarginMeters: 50,
  };
}

describe('isPointInsideControlledArea', () => {
  it('detects points inside and outside a simple polygon', () => {
    const area = rectangleArea();
    expect(isPointInsideControlledArea({ lng: 116.4, lat: 39.9 }, area)).toBe(true);
    expect(isPointInsideControlledArea({ lng: 117.2, lat: 39.1 }, area)).toBe(false);
    expect(isPointInsideControlledArea({ lng: 114.485, lat: 38.01 }, area)).toBe(false);
  });

  it('treats polygon holes as outside', () => {
    const area: ControlledArea = {
      ...rectangleArea(),
      geometry: {
        type: 'MultiPolygon',
        coordinates: [
          [
            [
              { lng: 116.0, lat: 39.0 },
              { lng: 117.0, lat: 39.0 },
              { lng: 117.0, lat: 40.0 },
              { lng: 116.0, lat: 40.0 },
              { lng: 116.0, lat: 39.0 },
            ],
            [
              { lng: 116.4, lat: 39.4 },
              { lng: 116.6, lat: 39.4 },
              { lng: 116.6, lat: 39.6 },
              { lng: 116.4, lat: 39.6 },
              { lng: 116.4, lat: 39.4 },
            ],
          ],
        ],
      },
    };
    expect(isPointInsideControlledArea({ lng: 116.3, lat: 39.3 }, area)).toBe(true);
    expect(isPointInsideControlledArea({ lng: 116.5, lat: 39.5 }, area)).toBe(false);
  });
});
