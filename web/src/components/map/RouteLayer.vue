<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue';

import { useRouteStore } from '@/stores/routeStore';
import type { SelectedPlace } from '@/types/coordinate';
import type { MapAdapter } from '@/types/map';

const props = defineProps<{ map: MapAdapter }>();
const routeStore = useRouteStore();
let fittedRouteId: string | null = null;

function withGcj02Fallback(
  place: SelectedPlace | null,
  fallback: { lng: number; lat: number } | undefined,
): SelectedPlace | null {
  if (!place || place.coordinate.coordinateSystem === 'GCJ02') return place;
  if (!fallback) return null;
  return {
    ...place,
    coordinate: { ...fallback, coordinateSystem: 'GCJ02' },
  };
}

function displayedEndpoints(): [SelectedPlace | null, SelectedPlace | null] {
  const geometry = routeStore.displayGeometry;
  const route = routeStore.route;
  if (!route || routeStore.routeView === 'full') {
    return [
      withGcj02Fallback(routeStore.start, geometry[0]),
      withGcj02Fallback(routeStore.end, geometry.at(-1)),
    ];
  }
  if (!route.navigationHandoff) {
    return route.planningMode === 'CROSS_BOUNDARY_OUTBOUND'
      ? [withGcj02Fallback(routeStore.start, geometry[0]), null]
      : [null, withGcj02Fallback(routeStore.end, geometry.at(-1))];
  }
  const handoff: SelectedPlace = {
    name: route.boundaryDirection === 'OUTBOUND' ? '环内路线终点' : '环内路线起点',
    coordinate: { ...route.navigationHandoff.gcj02, coordinateSystem: 'GCJ02' },
    source: 'MAP_PICK',
  };
  return route.planningMode === 'CROSS_BOUNDARY_OUTBOUND'
    ? [withGcj02Fallback(routeStore.start, geometry[0]), handoff]
    : [handoff, withGcj02Fallback(routeStore.end, geometry.at(-1))];
}

watch(
  () => [routeStore.start, routeStore.end, routeStore.route, routeStore.routeView] as const,
  () => {
    const route = routeStore.route;
    const [start, end] = displayedEndpoints();
    const outerEndpoint = route
      ? route.boundaryDirection === 'OUTBOUND'
        ? route.geometry.at(-1)
        : route.geometry[0]
      : undefined;
    props.map.setEndpointMarkers(start, end);
    props.map.setHandoffMarker(
      route?.boundaryCrossing && route.navigationHandoff && route.externalHandoff && outerEndpoint
        ? {
            crossing: route.boundaryCrossing,
            navigationHandoff: route.navigationHandoff,
            externalHandoff: route.externalHandoff,
            outerEndpoint,
          }
        : null,
    );
    if (!route) {
      props.map.clearRoute();
      fittedRouteId = null;
      return;
    }
    const geometry = routeStore.displayGeometry;
    props.map.setRoute(geometry);
    const displayRouteId = `${route.routeId}:${routeStore.routeView}`;
    if (fittedRouteId !== displayRouteId) {
      props.map.fitRoute(geometry);
      fittedRouteId = displayRouteId;
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  props.map.clearRoute();
  props.map.setEndpointMarkers(null, null);
  props.map.setHandoffMarker(null);
});
</script>

<template><span class="sr-only" data-testid="route-layer" /></template>
