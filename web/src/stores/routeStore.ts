import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getApiClient } from '@/services/createApiClient';
import { ApiClientError } from '@/services/errors';
import { ProtocolError } from '@/services/protocol';
import type { ApiClient } from '@/services/apiClient';
import type { RouteRequest, RouteResponse } from '@/types/api';
import type { BrowserLocation, InputCoordinate, SelectedPlace } from '@/types/coordinate';
import {
  NETWORK_ERROR_PRESENTATION,
  presentationForApiError,
  PROTOCOL_ERROR_PRESENTATION,
  type RouteErrorPresentation,
  type RouteFailureState,
} from '@/utils/routeErrors';

export type RoutePlanningState = 'idle' | 'planning' | 'success' | RouteFailureState;
export type PlanningKind = 'initial' | 'reroute' | null;

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
  const state = ref<RoutePlanningState>('idle');
  const planningKind = ref<PlanningKind>(null);
  const lastAttemptKind = ref<PlanningKind>(null);
  const error = ref<RouteErrorPresentation | null>(null);
  const requestSequence = ref(0);
  let activeController: AbortController | null = null;

  const canPlan = computed(() => Boolean(start.value && end.value) && state.value !== 'planning');
  const isPlanning = computed(() => state.value === 'planning');
  const hasRoute = computed(() => route.value !== null);

  function resetResult() {
    activeController?.abort();
    requestSequence.value += 1;
    route.value = null;
    state.value = 'idle';
    planningKind.value = null;
    lastAttemptKind.value = null;
    error.value = null;
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
      state.value = 'success';
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
    state,
    planningKind,
    lastAttemptKind,
    error,
    canPlan,
    isPlanning,
    hasRoute,
    setStart,
    setEnd,
    swapEndpoints,
    plan,
    rerouteFromLocation,
    cancelActiveRequest,
    resetResult,
  };
});
