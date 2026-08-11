<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ShieldCheck } from '@lucide/vue';

import MapToolbar from '@/components/map/MapToolbar.vue';
import MapView from '@/components/map/MapView.vue';
import RouteSearchPanel from '@/components/route/RouteSearchPanel.vue';
import RouteSummarySheet from '@/components/route/RouteSummarySheet.vue';
import { environment } from '@/config/environment';
import { getApiClient } from '@/services/createApiClient';
import type { ApiClient } from '@/services/apiClient';
import { useLocationStore } from '@/stores/locationStore';
import { useMapStore } from '@/stores/mapStore';
import { useRouteStore } from '@/stores/routeStore';
import type { Coordinate, SelectedPlace } from '@/types/coordinate';
import type { MapEndpointTarget, MapSelection, PlaceSuggestion } from '@/types/map';

interface MapViewExpose {
  searchPlaces(keyword: string, signal?: AbortSignal): Promise<PlaceSuggestion[]>;
  convertWgs84ToGcj02(coordinate: Coordinate): Promise<Coordinate & { coordinateSystem: 'GCJ02' }>;
  centerOn(coordinate: Coordinate): void;
}

const api = ref<ApiClient | null>(null);
const bootError = ref(false);
const mapView = ref<MapViewExpose | null>(null);
const pendingCurrentAsStart = ref(false);
const routeStore = useRouteStore();
const locationStore = useLocationStore();
const mapStore = useMapStore();
const appMode = computed(() => (environment.useMockApi ? '模拟数据' : '实时服务'));
const currentLocationStatus = computed(() => {
  if (!pendingCurrentAsStart.value) return null;
  if (locationStore.state === 'permission-denied')
    return '定位权限被拒绝，请允许当前站点访问位置后重试';
  if (locationStore.state === 'timeout') return '定位超时，请点击当前位置重试';
  if (locationStore.state === 'unavailable')
    return '浏览器无法提供位置，请检查系统定位或安全连接';
  if (locationStore.browserLocation && !locationStore.isFresh)
    return '当前位置已过期，正在重新获取';
  if (locationStore.browserLocation && !locationStore.isAccurate)
    return `当前精度约 ${Math.round(locationStore.browserLocation.accuracyMeters)} 米，正在等待精度提升`;
  return '正在获取当前位置';
});

async function boot() {
  bootError.value = false;
  try {
    api.value = await getApiClient();
    void loadSnapshot();
  } catch {
    bootError.value = true;
  }
}

async function loadSnapshot() {
  if (!api.value) return;
  try {
    mapStore.snapshot = await api.value.getCurrentCameraSnapshot();
    mapStore.snapshotError = false;
  } catch {
    mapStore.snapshotError = true;
  }
}

function searchPlaces(keyword: string, signal?: AbortSignal) {
  if (!mapView.value) return Promise.reject(new Error('地图尚未加载'));
  return mapView.value.searchPlaces(keyword, signal);
}

function requestLocation() {
  locationStore.startWatching((coordinate) => {
    if (!mapView.value) return Promise.reject(new Error('地图尚未加载'));
    return mapView.value.convertWgs84ToGcj02(coordinate);
  });
}

function useCurrentAsStart() {
  if (locationStore.browserLocation && locationStore.canUseForRoute) {
    routeStore.setStart(placeFromLocation());
    return;
  }
  pendingCurrentAsStart.value = true;
  requestLocation();
}

function placeFromLocation(): SelectedPlace {
  const location = locationStore.browserLocation;
  if (!location) throw new Error('当前位置不可用');
  return {
    name: '当前位置',
    coordinate: location.coordinate,
    source: 'CURRENT_LOCATION',
    accuracyMeters: location.accuracyMeters,
    timestamp: location.timestamp,
  };
}

function handleMapSelection(selection: MapSelection, target: MapEndpointTarget) {
  const place: SelectedPlace = {
    name: selection.suggestedName,
    coordinate: selection.coordinate,
    source: 'MAP_PICK',
  };
  if (target === 'start') routeStore.setStart(place);
  else routeStore.setEnd(place);
}

function centerOnCurrent() {
  if (locationStore.displayLocation)
    mapView.value?.centerOn(locationStore.displayLocation.coordinate);
  else requestLocation();
}

watch(
  () => [locationStore.browserLocation, locationStore.canUseForRoute] as const,
  ([location, canUseForRoute]) => {
    if (!location || !canUseForRoute || !pendingCurrentAsStart.value) return;
    pendingCurrentAsStart.value = false;
    routeStore.setStart(placeFromLocation());
  },
);

watch(
  () => routeStore.start,
  (start) => {
    if (pendingCurrentAsStart.value && start?.source !== 'CURRENT_LOCATION')
      pendingCurrentAsStart.value = false;
  },
);

onMounted(() => void boot());
onBeforeUnmount(() => {
  locationStore.stopWatching();
  routeStore.cancelActiveRequest();
});
</script>

<template>
  <main class="app-shell">
    <aside class="control-pane">
      <header class="app-header">
        <div class="app-title">
          <ShieldCheck :size="22" aria-hidden="true" />
          <div>
            <h1>摄像头避让路线</h1>
            <span>京津冀 · 驾车</span>
          </div>
        </div>
        <span class="mode-badge" :class="{ 'mode-badge--mock': environment.useMockApi }">
          {{ appMode }}
        </span>
      </header>

      <RouteSearchPanel
        :search-places="searchPlaces"
        :current-location-status="currentLocationStatus"
        @use-current="useCurrentAsStart"
      />
      <RouteSummarySheet />
    </aside>

    <section class="map-pane">
      <MapView v-if="api" ref="mapView" :api="api" @map-selection="handleMapSelection" />
      <div v-else class="map-state" role="status">
        <span v-if="!bootError" class="spinner" aria-hidden="true" />
        <template v-if="bootError">
          <strong>前端服务初始化失败</strong>
          <button class="button button--secondary" type="button" @click="boot">重试</button>
        </template>
        <span v-else>正在初始化</span>
      </div>
      <MapToolbar @request-location="requestLocation" @center-location="centerOnCurrent" />
      <div v-if="mapStore.snapshotError" class="map-toast" role="status">
        <span>当前点位快照状态不可用。</span>
        <button class="text-action" type="button" @click="loadSnapshot">重试</button>
      </div>
      <div v-else-if="mapStore.camerasError" class="map-toast" role="status">
        <span>{{ mapStore.camerasError }}</span>
        <button class="text-action" type="button" @click="mapStore.toggleCameras">关闭图层</button>
      </div>
    </section>
  </main>
</template>
