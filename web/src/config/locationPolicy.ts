export const LOCATION_POLICY = Object.freeze({
  maximumAgeMs: 30_000,
  maximumAccuracyMeters: 150,
  mapUpdateThrottleMs: 750,
  watchOptions: {
    enableHighAccuracy: true,
    timeout: 10_000,
    maximumAge: 2_000,
  } satisfies PositionOptions,
});
