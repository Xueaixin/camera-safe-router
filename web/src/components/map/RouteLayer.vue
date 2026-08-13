<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue';

import { useRouteStore } from '@/stores/routeStore';
import type { OutputCoordinate } from '@/types/api';
import type { SelectedPlace } from '@/types/coordinate';
import type { MapAdapter } from '@/types/map';
import { wgs84ToGcj02 } from '@/utils/wgs84ToGcj02';

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

function gcj02Endpoint(place: SelectedPlace | null): OutputCoordinate | undefined {
  if (!place) return undefined;
  const coordinate = place.coordinate;
  return coordinate.coordinateSystem === 'GCJ02'
    ? { lng: coordinate.lng, lat: coordinate.lat }
    : wgs84ToGcj02(coordinate.lng, coordinate.lat);
}

watch(
  () =>
    [
      routeStore.start,
      routeStore.end,
      routeStore.route,
      routeStore.routeView,
      routeStore.externalRoutes,
      routeStore.selectedExternalRoute,
    ] as const,
  () => {
    const route = routeStore.route;
    const [start, end] = displayedEndpoints();
    const outerEndpoint = route
      ? route.boundaryDirection === 'OUTBOUND'
        ? gcj02Endpoint(routeStore.end)
        : gcj02Endpoint(routeStore.start)
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
    const isExternalOnly = route.planningMode === 'EXTERNAL_ONLY';
    const isCrossBoundary =
      route.planningMode === 'CROSS_BOUNDARY_OUTBOUND' ||
      route.planningMode === 'CROSS_BOUNDARY_INBOUND';
    const showExternal = isExternalOnly || (isCrossBoundary && routeStore.routeView === 'full');
    const external = showExternal ? routeStore.externalRoutes : [];
    const selectedExternal = external[routeStore.selectedExternalRoute] ?? null;
    props.map.setExternalRoutes(
      external.length > 0 ? external : null,
      routeStore.selectedExternalRoute,
      routeStore.selectExternalRoute,
    );
    if (isExternalOnly) {
      props.map.setRoute([]);
    } else {
      props.map.setRoute(routeStore.displayGeometry);
    }
    const geometry = ((): OutputCoordinate[] => {
      if (isExternalOnly) {
        return selectedExternal?.geometry ?? route.geometry;
      }
      if (showExternal && selectedExternal) {
        return [...routeStore.displayGeometry, ...selectedExternal.geometry];
      }
      return routeStore.displayGeometry;
    })();
    const displayRouteId = `${route.routeId}:${routeStore.routeView}:${routeStore.selectedExternalRoute}`;
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
