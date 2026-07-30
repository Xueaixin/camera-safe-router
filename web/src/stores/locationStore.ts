import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { LOCATION_POLICY } from '@/config/locationPolicy';
import type { BrowserLocation, Coordinate, DisplayLocation } from '@/types/coordinate';

export type LocationState =
  'idle' | 'requesting' | 'watching' | 'permission-denied' | 'unavailable' | 'timeout';

export type LocationConverter = (
  coordinate: Coordinate,
) => Promise<Coordinate & { coordinateSystem: 'GCJ02' }>;

export const useLocationStore = defineStore('location', () => {
  const state = ref<LocationState>('idle');
  const browserLocation = ref<BrowserLocation | null>(null);
  const displayLocation = ref<DisplayLocation | null>(null);
  const conversionError = ref(false);
  const clock = ref(Date.now());
  let watchId: number | null = null;
  let ageTimer: number | null = null;
  let geolocationSource: Geolocation | null = null;
  let updateSequence = 0;
  let visibilityHandler: (() => void) | null = null;

  const ageMs = computed(() => {
    if (!browserLocation.value) return Number.POSITIVE_INFINITY;
    return clock.value - new Date(browserLocation.value.timestamp).getTime();
  });
  const isFresh = computed(() => ageMs.value <= LOCATION_POLICY.maximumAgeMs);
  const isAccurate = computed(
    () =>
      browserLocation.value !== null &&
      browserLocation.value.accuracyMeters <= LOCATION_POLICY.maximumAccuracyMeters,
  );
  const canUseForRoute = computed(
    () => state.value === 'watching' && isFresh.value && isAccurate.value,
  );

  function startWatching(
    converter: LocationConverter,
    source: Geolocation | undefined = navigator.geolocation,
  ) {
    stopWatching();
    if (!source) {
      state.value = 'unavailable';
      return;
    }
    geolocationSource = source;
    state.value = 'requesting';
    conversionError.value = false;
    clock.value = Date.now();
    ageTimer = window.setInterval(() => {
      clock.value = Date.now();
    }, 1_000);
    watchId = source.watchPosition(
      (position) => {
        const sequence = ++updateSequence;
        const raw: BrowserLocation = {
          coordinate: {
            lng: position.coords.longitude,
            lat: position.coords.latitude,
            coordinateSystem: 'WGS84',
          },
          accuracyMeters: position.coords.accuracy,
          heading: position.coords.heading,
          speed: position.coords.speed,
          timestamp: new Date(position.timestamp).toISOString(),
        };
        clock.value = Date.now();
        browserLocation.value = raw;
        state.value = 'watching';
        void converter(raw.coordinate)
          .then((converted) => {
            if (sequence !== updateSequence) return;
            displayLocation.value = {
              coordinate: converted,
              accuracyMeters: raw.accuracyMeters,
              timestamp: raw.timestamp,
            };
            conversionError.value = false;
          })
          .catch(() => {
            if (sequence !== updateSequence) return;
            displayLocation.value = null;
            conversionError.value = true;
          });
      },
      (positionError) => {
        if (positionError.code === positionError.PERMISSION_DENIED)
          state.value = 'permission-denied';
        else if (positionError.code === positionError.TIMEOUT) state.value = 'timeout';
        else state.value = 'unavailable';
      },
      LOCATION_POLICY.watchOptions,
    );
    visibilityHandler = () => {
      clock.value = Date.now();
      if (document.visibilityState === 'visible' && browserLocation.value && !isFresh.value) {
        state.value = 'requesting';
      }
    };
    document.addEventListener('visibilitychange', visibilityHandler);
  }

  function stopWatching() {
    if (watchId !== null && geolocationSource) geolocationSource.clearWatch(watchId);
    if (ageTimer !== null) window.clearInterval(ageTimer);
    if (visibilityHandler) document.removeEventListener('visibilitychange', visibilityHandler);
    watchId = null;
    ageTimer = null;
    geolocationSource = null;
    visibilityHandler = null;
  }

  function reset() {
    stopWatching();
    updateSequence += 1;
    state.value = 'idle';
    browserLocation.value = null;
    displayLocation.value = null;
    conversionError.value = false;
  }

  return {
    state,
    browserLocation,
    displayLocation,
    conversionError,
    ageMs,
    isFresh,
    isAccurate,
    canUseForRoute,
    startWatching,
    stopWatching,
    reset,
  };
});
