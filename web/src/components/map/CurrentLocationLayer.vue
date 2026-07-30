<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue';

import { LOCATION_POLICY } from '@/config/locationPolicy';
import { useLocationStore } from '@/stores/locationStore';
import type { MapAdapter } from '@/types/map';

const props = defineProps<{ map: MapAdapter }>();
const locationStore = useLocationStore();
let timer: number | null = null;
let lastDrawnAt = 0;

watch(
  () => locationStore.displayLocation,
  (location) => {
    if (!location) {
      props.map.setCurrentLocation(null);
      return;
    }
    const elapsed = Date.now() - lastDrawnAt;
    const draw = () => {
      timer = null;
      lastDrawnAt = Date.now();
      props.map.setCurrentLocation(locationStore.displayLocation);
    };
    if (elapsed >= LOCATION_POLICY.mapUpdateThrottleMs) draw();
    else {
      if (timer !== null) window.clearTimeout(timer);
      timer = window.setTimeout(draw, LOCATION_POLICY.mapUpdateThrottleMs - elapsed);
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  if (timer !== null) window.clearTimeout(timer);
  props.map.setCurrentLocation(null);
});
</script>

<template><span class="sr-only" data-testid="current-location-layer" /></template>
