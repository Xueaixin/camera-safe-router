import { onBeforeUnmount } from 'vue';

import { useLocationStore, type LocationConverter } from '@/stores/locationStore';

export function useGeolocation(converter: LocationConverter) {
  const store = useLocationStore();

  function requestLocation() {
    store.startWatching(converter);
  }

  onBeforeUnmount(() => store.stopWatching());

  return {
    locationStore: store,
    requestLocation,
    stopLocation: store.stopWatching,
  };
}
