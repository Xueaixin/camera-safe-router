import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getApiClient } from '@/services/createApiClient';
import { ApiClientError } from '@/services/errors';
import { fetchOsrmRoutes } from '@/services/osrmClient';
import { ProtocolError } from '@/services/protocol';
import type { ApiClient } from '@/services/apiClient';
import type { RouteRequest, RouteResponse } from '@/types/api';
import type { BrowserLocation, InputCoordinate, SelectedPlace } from '@/types/coordinate';
import type { ExternalRoute } from '@/types/map';
import { gcj02ToWgs84 } from '@/utils/wgs84ToGcj02';
import {
  NETWORK_ERROR_PRESENTATION,
  presentationForApiError,
  PROTOCOL_ERROR_PRESENTATION,
  type RouteErrorPresentation,
  type RouteFailureState,
} from '@/utils/routeErrors';

export type RoutePlanningState = 'idle' | 'planning' | 'success' | RouteFailureState;
export type PlanningKind = 'initial' | 'reroute' | null;
export type RouteViewMode = 'full' | 'safe-segment';

function toInputCoordinate(place: SelectedPlace): InputCoordinate {
  return {
    lng: place.coordinate.lng,
    lat: place.coordinate.lat,
    coordinateSystem: place.coordinate.coordinateSystem,
    source: place.source,
    ...(place.accuracyMeters !== undefined ? { accuracyMeters: place.accuracyMeters } : {}),
    ...(place.timestamp !== undefined ? { timestamp: place.timestamp } : {}),
  };
}

function currentLocationPlace(location: BrowserLocation): SelectedPlace {
  return {
    name: '当前位置',
    coordinate: location.coordinate,
    source: 'CURRENT_LOCATION',
    accuracyMeters: location.accuracyMeters,
    timestamp: location.timestamp,
  };
}

export const useRouteStore = defineStore('route', () => {
  const start = ref<SelectedPlace | null>(null);
  const end = ref<SelectedPlace | null>(null);
  const route = ref<RouteResponse | null>(null);
  const routeView = ref<RouteViewMode>('full');
  const state = ref<RoutePlanningState>('idle');
  const planningKind = ref<PlanningKind>(null);
  const lastAttemptKind = ref<PlanningKind>(null);
  const error = ref<RouteErrorPresentation | null>(null);
  const requestSequence = ref(0);
  const externalRoutes = ref<ExternalRoute[]>([]);
  const selectedExternalRoute = ref(0);
  const externalRouteState = ref<'idle' | 'loading' | 'error'>('idle');
  const externalRouteError = ref('');
  let activeController: AbortController | null = null;
  let externalController: AbortController | null = null;

  const canPlan = computed(() => Boolean(start.value && end.value) && state.value !== 'planning');
  const isPlanning = computed(() => state.value === 'planning');
  const hasRoute = computed(() => route.value !== null);
  const isCrossBoundary = computed(
    () =>
      route.value?.planningMode === 'CROSS_BOUNDARY_OUTBOUND' ||
      route.value?.planningMode === 'CROSS_BOUNDARY_INBOUND',
  );
  const displaySegment = computed(() =>
    routeView.value === 'safe-segment' && isCrossBoundary.value
      ? (route.value?.safeSegment ?? null)
      : null,
  );
  const displayGeometry = computed(
    () => displaySegment.value?.geometry ?? route.value?.geometry ?? [],
  );
  const displayDistanceMeters = computed(() => {
    const segment = displaySegment.value;
    if (segment) return segment.distanceMeters;
    const external = externalRoutes.value[selectedExternalRoute.value];
    return (route.value?.distanceMeters ?? 0) + (external?.distanceMeters ?? 0);
  });
  const displayDurationSeconds = computed(() => {
    const segment = displaySegment.value;
    if (segment) return segment.durationSeconds;
    const external = externalRoutes.value[selectedExternalRoute.value];
    return (route.value?.durationSeconds ?? 0) + (external?.durationSeconds ?? 0);
  });

  function resetResult() {
    activeController?.abort();
    requestSequence.value += 1;
    route.value = null;
    routeView.value = 'full';
    state.value = 'idle';
    planningKind.value = null;
    lastAttemptKind.value = null;
    error.value = null;
    externalController?.abort();
    externalController = null;
    externalRoutes.value = [];
    selectedExternalRoute.value = 0;
    externalRouteState.value = 'idle';
    externalRouteError.value = '';
  }

  function setStart(place: SelectedPlace | null) {
    start.value = place;
    resetResult();
  }

  function setEnd(place: SelectedPlace | null) {
    end.value = place;
    resetResult();
  }

  function swapEndpoints() {
    const previousStart = start.value;
    start.value = end.value;
    end.value = previousStart;
    resetResult();
  }

  function cancelActiveRequest() {
    activeController?.abort();
    activeController = null;
  }

  function showFullRoute() {
    routeView.value = 'full';
  }

  function showSafeSegment() {
    if (isCrossBoundary.value && route.value?.safeSegment) {
      routeView.value = 'safe-segment';
    }
  }

  function selectExternalRoute(index: number) {
    if (index >= 0 && index < externalRoutes.value.length) {
      selectedExternalRoute.value = index;
    }
  }

  function wgs84ForPlace(place: SelectedPlace): { lng: number; lat: number } {
    const coordinate = place.coordinate;
    return coordinate.coordinateSystem === 'GCJ02'
      ? gcj02ToWgs84(coordinate.lng, coordinate.lat)
      : { lng: coordinate.lng, lat: coordinate.lat };
  }

  async function loadExternalRoutes(route: RouteResponse): Promise<void> {
    externalController?.abort();
    const controller = new AbortController();
    externalController = controller;
    externalRoutes.value = [];
    selectedExternalRoute.value = 0;
    externalRouteState.value = 'loading';
    externalRouteError.value = '';
    let from: { lng: number; lat: number } | null = null;
    let to: { lng: number; lat: number } | null = null;
    if (route.planningMode === 'CROSS_BOUNDARY_OUTBOUND' && route.navigationHandoff && end.value) {
      from = route.navigationHandoff.wgs84;
      to = wgs84ForPlace(end.value);
    } else if (
      route.planningMode === 'CROSS_BOUNDARY_INBOUND' &&
      route.navigationHandoff &&
      start.value
    ) {
      from = wgs84ForPlace(start.value);
      to = route.navigationHandoff.wgs84;
    } else if (route.planningMode === 'EXTERNAL_ONLY' && start.value && end.value) {
      from = wgs84ForPlace(start.value);
      to = wgs84ForPlace(end.value);
    }
    if (!from || !to) {
      externalRouteState.value = 'idle';
      return;
    }
    try {
      const osrmRoutes = await fetchOsrmRoutes(from, to, controller.signal);
      if (controller.signal.aborted) return;
      externalRoutes.value = osrmRoutes.slice(0, 3).map((osrmRoute, index) => ({
        id: `osrm-${route.routeId}-${index}`,
        distanceMeters: osrmRoute.distanceMeters,
        durationSeconds: osrmRoute.durationSeconds,
        geometry: osrmRoute.geometry,
      }));
      selectedExternalRoute.value = 0;
      externalRouteState.value = 'idle';
    } catch (caught: unknown) {
      if (controller.signal.aborted) return;
      externalRouteState.value = 'error';
      externalRouteError.value = caught instanceof Error ? caught.message : 'OSRM 请求失败';
    }
  }

  async function plan(client?: ApiClient): Promise<boolean> {
    if (!start.value || !end.value || state.value === 'planning') return false;
    route.value = null;
    return execute(
      {
        start: toInputCoordinate(start.value),
        end: toInputCoordinate(end.value),
        vehicle: 'CAR',
      },
      'initial',
      client,
    );
  }

  async function rerouteFromLocation(
    location: BrowserLocation,
    client?: ApiClient,
  ): Promise<boolean> {
    if (!end.value || state.value === 'planning') return false;
    const pendingStart = currentLocationPlace(location);
    const requestEnd = end.value;
    const succeeded = await execute(
      {
        start: toInputCoordinate(pendingStart),
        end: toInputCoordinate(requestEnd),
        vehicle: 'CAR',
      },
      'reroute',
      client,
    );
    if (succeeded) {
      start.value = pendingStart;
      end.value = requestEnd;
    }
    return succeeded;
  }

  async function execute(
    request: RouteRequest,
    kind: Exclude<PlanningKind, null>,
    suppliedClient?: ApiClient,
  ): Promise<boolean> {
    cancelActiveRequest();
    const controller = new AbortController();
    activeController = controller;
    const sequence = ++requestSequence.value;
    state.value = 'planning';
    planningKind.value = kind;
    lastAttemptKind.value = kind;
    error.value = null;
    try {
      const client = suppliedClient ?? (await getApiClient());
      const response = await client.planRoute(request, controller.signal);
      if (sequence !== requestSequence.value || controller.signal.aborted) return false;
      if (response.coordinateSystem !== 'GCJ02') {
        throw new ProtocolError('路线不是 GCJ02，不能绘制到高德地图');
      }
      route.value = response;
      routeView.value = 'full';
      state.value = 'success';
      void loadExternalRoutes(response);
      return true;
    } catch (caught: unknown) {
      if (sequence !== requestSequence.value || controller.signal.aborted) return false;
      if (caught instanceof DOMException && caught.name === 'AbortError') return false;
      const presentation =
        caught instanceof ApiClientError
          ? presentationForApiError(caught.payload.code)
          : caught instanceof ProtocolError
            ? PROTOCOL_ERROR_PRESENTATION
            : NETWORK_ERROR_PRESENTATION;
      error.value = presentation;
      state.value = presentation.state;
      return false;
    } finally {
      if (sequence === requestSequence.value) {
        planningKind.value = null;
        activeController = null;
      }
    }
  }

  return {
    start,
    end,
    route,
    routeView,
    state,
    planningKind,
    lastAttemptKind,
    error,
    canPlan,
    isPlanning,
    hasRoute,
    isCrossBoundary,
    displayGeometry,
    displayDistanceMeters,
    displayDurationSeconds,
    setStart,
    setEnd,
    swapEndpoints,
    plan,
    rerouteFromLocation,
    cancelActiveRequest,
    showFullRoute,
    showSafeSegment,
    selectExternalRoute,
    externalRoutes,
    selectedExternalRoute,
    externalRouteState,
    externalRouteError,
    resetResult,
  };
});
