import { ref } from 'vue';
import { defineStore } from 'pinia';

import type { CameraSnapshotStatus, CameraView } from '@/types/api';

export type SelectionTarget = 'start' | 'end' | null;

export const useMapStore = defineStore('map', () => {
  const selectionTarget = ref<SelectionTarget>(null);
  const camerasVisible = ref(true);
  const visibleCameras = ref<CameraView[]>([]);
  const camerasLoading = ref(false);
  const camerasError = ref<string | null>(null);
  const snapshot = ref<CameraSnapshotStatus | null>(null);
  const snapshotError = ref(false);

  function beginSelection(target: Exclude<SelectionTarget, null>) {
    selectionTarget.value = target;
  }

  function finishSelection() {
    selectionTarget.value = null;
  }

  function toggleCameras() {
    camerasVisible.value = !camerasVisible.value;
  }

  return {
    selectionTarget,
    camerasVisible,
    visibleCameras,
    camerasLoading,
    camerasError,
    snapshot,
    snapshotError,
    beginSelection,
    finishSelection,
    toggleCameras,
  };
});
