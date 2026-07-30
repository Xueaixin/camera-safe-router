import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLocationStore } from '@/stores/locationStore';

interface PositionCallbacks {
  success?: PositionCallback;
  error?: PositionErrorCallback | undefined;
}

function geolocationStub(callbacks: PositionCallbacks): Geolocation {
  return {
    clearWatch: vi.fn(),
    getCurrentPosition: vi.fn(),
    watchPosition: vi.fn((success: PositionCallback, error: PositionErrorCallback | null) => {
      callbacks.success = success;
      callbacks.error = error ?? undefined;
      return 7;
    }),
  };
}

function position(longitude: number, latitude: number, accuracy = 20): GeolocationPosition {
  return {
    coords: {
      longitude,
      latitude,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
      toJSON: () => ({}),
    },
    timestamp: Date.now(),
    toJSON: () => ({}),
  };
}

describe('location store', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('keeps raw WGS84 separate from converted GCJ02 display position', async () => {
    const callbacks: PositionCallbacks = {};
    const source = geolocationStub(callbacks);
    const store = useLocationStore();
    const routeRequest = vi.fn();
    store.startWatching(
      async (coordinate) => ({
        lng: coordinate.lng + 0.006,
        lat: coordinate.lat + 0.001,
        coordinateSystem: 'GCJ02',
      }),
      source,
    );
    callbacks.success?.(position(116.39, 39.9));
    await Promise.resolve();

    expect(store.state).toBe('watching');
    expect(store.browserLocation?.coordinate).toEqual({
      lng: 116.39,
      lat: 39.9,
      coordinateSystem: 'WGS84',
    });
    expect(store.displayLocation?.coordinate.coordinateSystem).toBe('GCJ02');
    expect(routeRequest).not.toHaveBeenCalled();
  });

  it('maps geolocation denial and timeout to explicit states', () => {
    const callbacks: PositionCallbacks = {};
    const store = useLocationStore();
    store.startWatching(async () => {
      throw new Error('unused');
    }, geolocationStub(callbacks));
    callbacks.error?.({
      code: 1,
      message: 'denied',
      PERMISSION_DENIED: 1,
      POSITION_UNAVAILABLE: 2,
      TIMEOUT: 3,
    });
    expect(store.state).toBe('permission-denied');
    callbacks.error?.({
      code: 3,
      message: 'timeout',
      PERMISSION_DENIED: 1,
      POSITION_UNAVAILABLE: 2,
      TIMEOUT: 3,
    });
    expect(store.state).toBe('timeout');
  });

  it('marks a position stale after the centralized 30 second policy', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-30T00:00:00Z'));
    const callbacks: PositionCallbacks = {};
    const store = useLocationStore();
    store.startWatching(
      async (coordinate) => ({ ...coordinate, coordinateSystem: 'GCJ02' }),
      geolocationStub(callbacks),
    );
    callbacks.success?.(position(116.39, 39.9));
    await Promise.resolve();
    expect(store.isFresh).toBe(true);

    await vi.advanceTimersByTimeAsync(31_000);
    expect(store.isFresh).toBe(false);
    store.reset();
    vi.useRealTimers();
  });
});
