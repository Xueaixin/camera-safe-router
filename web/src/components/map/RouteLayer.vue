<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue';

import { useRouteStore } from '@/stores/routeStore';
import type { SelectedPlace } from '@/types/coordinate';
import type { MapAdapter } from '@/types/map';

const props = defineProps<{ map: MapAdapter }>();
const routeStore = useRouteStore();
let fittedRouteId: string | null = null;

function gcjStart(): SelectedPlace | null {
  const start = routeStore.start;
  if (!start || start.coordinate.coordinateSystem === 'GCJ02') return start;
  const first = routeStore.route?.geometry[0];
  if (!first) return null;
  return {
    ...start,
    coordinate: { ...first, coordinateSystem: 'GCJ02' },
  };
}

watch(
  () => [routeStore.start, routeStore.end, routeStore.route] as const,
  () => {
    props.map.setEndpointMarkers(gcjStart(), routeStore.end);
    if (!routeStore.route) {
      props.map.clearRoute();
      fittedRouteId = null;
      return;
    }
    props.map.setRoute(routeStore.route.geometry);
    if (fittedRouteId !== routeStore.route.routeId) {
      props.map.fitRoute(routeStore.route.geometry);
      fittedRouteId = routeStore.route.routeId;
    }
  },
  { immediate: true, deep: true },
);

onBeforeUnmount(() => {
  props.map.clearRoute();
  props.map.setEndpointMarkers(null, null);
});
</script>

<template><span class="sr-only" data-testid="route-layer" /></template>
