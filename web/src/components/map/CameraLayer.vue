<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue';

import { createCameraViewportController } from '@/composables/useCameraViewport';
import type { ApiClient } from '@/services/apiClient';
import { useMapStore } from '@/stores/mapStore';
import type { MapAdapter } from '@/types/map';

const props = defineProps<{
  map: MapAdapter;
  api: ApiClient;
}>();

const mapStore = useMapStore();
const controller = createCameraViewportController(props.map, props.api, {
  onLoading: (loading) => {
    mapStore.camerasLoading = loading;
  },
  onData: (cameras) => {
    mapStore.visibleCameras = cameras;
    if (mapStore.camerasVisible) props.map.setCameras(cameras);
    else props.map.clearCameras();
  },
  onError: (message) => {
    mapStore.camerasError = message;
  },
});

watch(
  () => mapStore.camerasVisible,
  (visible) => {
    controller.setEnabled(visible);
    if (visible) props.map.setCameras(mapStore.visibleCameras);
    else props.map.clearCameras();
  },
);

onMounted(() => controller.start());
onBeforeUnmount(() => {
  controller.stop();
  props.map.clearCameras();
});
</script>

<template>
  <span class="sr-only" aria-live="polite">
    {{ mapStore.camerasLoading ? '正在加载摄像头点位' : '' }}
  </span>
</template>
