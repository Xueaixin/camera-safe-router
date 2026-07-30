import type { ApiClient } from '@/services/apiClient';
import { ProtocolError } from '@/services/protocol';
import type { CameraView } from '@/types/api';
import type { MapAdapter, MapViewport } from '@/types/map';

export interface CameraViewportCallbacks {
  onLoading: (loading: boolean) => void;
  onData: (cameras: CameraView[]) => void;
  onError: (message: string | null) => void;
}

export interface CameraViewportController {
  start(): void;
  stop(): void;
  setEnabled(enabled: boolean): void;
  reload(): void;
}

function viewportChanged(previous: MapViewport | null, next: MapViewport): boolean {
  if (!previous || previous.zoom !== next.zoom) return true;
  const width = Math.max(previous.maxLng - previous.minLng, 0.0001);
  const height = Math.max(previous.maxLat - previous.minLat, 0.0001);
  const oldCenterLng = (previous.minLng + previous.maxLng) / 2;
  const oldCenterLat = (previous.minLat + previous.maxLat) / 2;
  const newCenterLng = (next.minLng + next.maxLng) / 2;
  const newCenterLat = (next.minLat + next.maxLat) / 2;
  return (
    Math.abs(newCenterLng - oldCenterLng) / width > 0.08 ||
    Math.abs(newCenterLat - oldCenterLat) / height > 0.08
  );
}

export function createCameraViewportController(
  map: MapAdapter,
  api: ApiClient,
  callbacks: CameraViewportCallbacks,
  debounceMs = 350,
): CameraViewportController {
  let enabled = true;
  let timer: number | null = null;
  let unsubscribe: (() => void) | null = null;
  let activeController: AbortController | null = null;
  let requestSequence = 0;
  let lastViewport: MapViewport | null = null;

  const cancel = () => {
    if (timer !== null) window.clearTimeout(timer);
    timer = null;
    activeController?.abort();
    activeController = null;
  };

  const load = async (force = false) => {
    timer = null;
    if (!enabled) return;
    const viewport = map.getViewport();
    if (!force && !viewportChanged(lastViewport, viewport)) return;
    lastViewport = viewport;
    activeController?.abort();
    const controller = new AbortController();
    activeController = controller;
    const sequence = ++requestSequence;
    callbacks.onLoading(true);
    callbacks.onError(null);
    try {
      const page = await api.listCameras(
        {
          minLng: viewport.minLng,
          minLat: viewport.minLat,
          maxLng: viewport.maxLng,
          maxLat: viewport.maxLat,
        },
        'GCJ02',
        controller.signal,
      );
      if (sequence !== requestSequence || controller.signal.aborted) return;
      if (page.coordinateSystem !== 'GCJ02') {
        throw new ProtocolError('摄像头响应坐标系与地图不一致');
      }
      callbacks.onData(page.items);
    } catch (error: unknown) {
      if (sequence !== requestSequence || controller.signal.aborted) return;
      callbacks.onError(
        error instanceof ProtocolError ? error.message : '摄像头点位加载失败，请稍后重试。',
      );
    } finally {
      if (sequence === requestSequence) callbacks.onLoading(false);
    }
  };

  const schedule = (force = false) => {
    if (!enabled) return;
    if (timer !== null) window.clearTimeout(timer);
    timer = window.setTimeout(() => void load(force), force ? 0 : debounceMs);
  };

  return {
    start() {
      if (unsubscribe) return;
      unsubscribe = map.subscribeViewport(() => schedule(false));
      schedule(true);
    },
    stop() {
      cancel();
      unsubscribe?.();
      unsubscribe = null;
      requestSequence += 1;
      callbacks.onLoading(false);
    },
    setEnabled(nextEnabled: boolean) {
      enabled = nextEnabled;
      if (!enabled) {
        cancel();
        requestSequence += 1;
        callbacks.onData([]);
        callbacks.onLoading(false);
      } else {
        lastViewport = null;
        schedule(true);
      }
    },
    reload() {
      lastViewport = null;
      schedule(true);
    },
  };
}
