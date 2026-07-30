<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { AlertTriangle, RefreshCw } from '@lucide/vue';

import CameraLayer from './CameraLayer.vue';
import CurrentLocationLayer from './CurrentLocationLayer.vue';
import RouteLayer from './RouteLayer.vue';
import { resetAmapLoader } from '@/maps/amapLoader';
import { createMapAdapter } from '@/maps/createMapAdapter';
import type { ApiClient } from '@/services/apiClient';
import type { CameraView } from '@/types/api';
import type { Coordinate } from '@/types/coordinate';
import type { MapAdapter, MapSelection, PlaceSuggestion } from '@/types/map';

defineProps<{ api: ApiClient }>();
const emit = defineEmits<{ mapSelection: [selection: MapSelection] }>();

const container = ref<HTMLElement | null>(null);
const map = shallowRef<MapAdapter | null>(null);
const loadState = ref<'loading' | 'ready' | 'error'>('loading');
const loadError = ref('');
const selectedCamera = ref<CameraView | null>(null);

async function initialize() {
  if (!container.value) return;
  map.value?.destroy();
  map.value = null;
  loadState.value = 'loading';
  loadError.value = '';
  try {
    const nextMap = await createMapAdapter();
    await nextMap.initialize(container.value, {
      onMapClick: (selection) => emit('mapSelection', selection),
      onCameraClick: (camera) => {
        selectedCamera.value = camera;
      },
    });
    map.value = nextMap;
    loadState.value = 'ready';
  } catch (error: unknown) {
    loadError.value = error instanceof Error ? error.message : '地图加载失败';
    loadState.value = 'error';
  }
}

function retry() {
  resetAmapLoader();
  void initialize();
}

function waitUntilReady(): Promise<MapAdapter> {
  if (map.value) return Promise.resolve(map.value);
  if (loadState.value === 'error') return Promise.reject(new Error(loadError.value));
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      stop();
      reject(new Error('地图初始化超时'));
    }, 5_000);
    const stop = watch([map, loadState], ([readyMap, state]) => {
      if (readyMap) {
        window.clearTimeout(timeout);
        stop();
        resolve(readyMap);
      } else if (state === 'error') {
        window.clearTimeout(timeout);
        stop();
        reject(new Error(loadError.value));
      }
    });
  });
}

async function searchPlaces(keyword: string, signal?: AbortSignal): Promise<PlaceSuggestion[]> {
  const readyMap = await waitUntilReady();
  return readyMap.searchPlaces(keyword, signal);
}

async function convertWgs84ToGcj02(coordinate: Coordinate) {
  const readyMap = await waitUntilReady();
  return readyMap.convertWgs84ToGcj02(coordinate);
}

function centerOn(coordinate: Coordinate) {
  map.value?.centerOn(coordinate);
}

defineExpose({ searchPlaces, convertWgs84ToGcj02, centerOn });

onMounted(() => void initialize());
onBeforeUnmount(() => map.value?.destroy());
</script>

<template>
  <section class="map-shell" aria-label="路线地图">
    <div ref="container" class="map-container" data-testid="map-container" />

    <div v-if="loadState === 'loading'" class="map-state" role="status" aria-live="polite">
      <span class="spinner" aria-hidden="true" />
      <span>正在加载地图</span>
    </div>

    <div v-else-if="loadState === 'error'" class="map-state map-state--error" role="alert">
      <AlertTriangle :size="22" aria-hidden="true" />
      <strong>地图无法加载</strong>
      <span>{{ loadError }}</span>
      <button class="button button--secondary" type="button" @click="retry">
        <RefreshCw :size="17" aria-hidden="true" />
        重试
      </button>
    </div>

    <template v-if="map && loadState === 'ready'">
      <RouteLayer :map="map" />
      <CameraLayer :map="map" :api="api" />
      <CurrentLocationLayer :map="map" />
    </template>

    <div v-if="selectedCamera" class="camera-detail" data-testid="camera-detail">
      <button
        class="camera-detail__close"
        type="button"
        aria-label="关闭摄像头详情"
        title="关闭"
        @click="selectedCamera = null"
      >
        ×
      </button>
      <strong>{{ selectedCamera.cameraType }}</strong>
      <span>{{ selectedCamera.address }}</span>
      <span>方向：{{ selectedCamera.directionText || '未标注' }}</span>
    </div>

    <a
      class="osm-attribution"
      href="https://www.openstreetmap.org/copyright"
      target="_blank"
      rel="noreferrer"
    >
      © OpenStreetMap contributors
    </a>
  </section>
</template>
