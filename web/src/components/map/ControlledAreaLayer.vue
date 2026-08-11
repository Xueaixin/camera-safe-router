<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue';

import type { ApiClient } from '@/services/apiClient';
import { useMapStore } from '@/stores/mapStore';
import type { MapAdapter } from '@/types/map';

const props = defineProps<{ map: MapAdapter; api: ApiClient }>();
const mapStore = useMapStore();
const controller = new AbortController();

onMounted(async () => {
  try {
    const area = await props.api.getControlledArea(controller.signal);
    mapStore.controlledArea = area;
    mapStore.controlledAreaError = false;
    if (mapStore.controlledAreaVisible) props.map.setControlledArea(area);
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
    mapStore.controlledAreaError = true;
    props.map.clearControlledArea();
  }
});

watch(
  () => mapStore.controlledAreaVisible,
  (visible) => {
    if (visible && mapStore.controlledArea) props.map.setControlledArea(mapStore.controlledArea);
    else props.map.clearControlledArea();
  },
);

onBeforeUnmount(() => {
  controller.abort();
  props.map.clearControlledArea();
});
</script>

<template>
  <span v-if="false" />
</template>
