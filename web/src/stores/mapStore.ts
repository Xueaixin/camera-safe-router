import { ref } from 'vue';
import { defineStore } from 'pinia';

import type { CameraSnapshotStatus, CameraView, ControlledArea } from '@/types/api';

export const useMapStore = defineStore('map', () => {
  const camerasVisible = ref(true);
  const visibleCameras = ref<CameraView[]>([]);
  const camerasLoading = ref(false);
  const camerasError = ref<string | null>(null);
  const snapshot = ref<CameraSnapshotStatus | null>(null);
  const snapshotError = ref(false);
  const controlledAreaVisible = ref(true);
  const controlledArea = ref<ControlledArea | null>(null);
  const controlledAreaError = ref(false);

  function toggleCameras() {
    camerasVisible.value = !camerasVisible.value;
  }

  function toggleControlledArea() {
    controlledAreaVisible.value = !controlledAreaVisible.value;
  }

  return {
    camerasVisible,
    visibleCameras,
    camerasLoading,
    camerasError,
    snapshot,
    snapshotError,
    controlledAreaVisible,
    controlledArea,
    controlledAreaError,
    toggleCameras,
    toggleControlledArea,
  };
});
