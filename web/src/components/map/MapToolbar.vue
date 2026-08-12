<script setup lang="ts">
import { computed } from 'vue';
import { Crosshair, EyeOff, LandPlot, Layers3, LocateFixed, RefreshCw } from '@lucide/vue';

import { LOCATION_POLICY } from '@/config/locationPolicy';
import { useLocationStore } from '@/stores/locationStore';
import { useMapStore } from '@/stores/mapStore';
import { useRouteStore } from '@/stores/routeStore';

const emit = defineEmits<{ requestLocation: []; centerLocation: [] }>();
const locationStore = useLocationStore();
const mapStore = useMapStore();
const routeStore = useRouteStore();

const locationLabel = computed(() => {
  if (locationStore.state === 'requesting') return '正在获取位置';
  if (locationStore.state === 'permission-denied') return '定位权限被拒绝，点击重试';
  if (locationStore.state === 'timeout') return '定位超时，点击重试';
  if (locationStore.state === 'unavailable') return '定位不可用，点击重试';
  if (locationStore.displayLocation && locationStore.state === 'watching') return '回到当前位置';
  return '获取当前位置';
});

const rerouteDisabledReason = computed(() => {
  if (!routeStore.end) return '请先选择终点';
  if (routeStore.isPlanning) return '路线规划正在进行';
  if (!locationStore.browserLocation) return '尚未获取当前位置';
  if (!locationStore.isFresh) return '当前位置已超过 30 秒，请重新获取';
  if (!locationStore.isAccurate)
    return `定位精度差于 ${LOCATION_POLICY.maximumAccuracyMeters} 米，请等待更准确的位置`;
  if (locationStore.state !== 'watching') return '当前位置暂不可用';
  return '';
});

function handleLocation() {
  if (locationStore.displayLocation && locationStore.state === 'watching') emit('centerLocation');
  else emit('requestLocation');
}

function handleReroute() {
  if (rerouteDisabledReason.value || !locationStore.browserLocation) return;
  void routeStore.rerouteFromLocation(locationStore.browserLocation);
}
</script>

<template>
  <div class="map-toolbar" aria-label="地图工具">
    <button
      class="map-tool-button"
      type="button"
      :aria-label="locationLabel"
      :title="locationLabel"
      :disabled="locationStore.state === 'requesting'"
      data-testid="location-button"
      @click="handleLocation"
    >
      <span v-if="locationStore.state === 'requesting'" class="spinner spinner--small" />
      <LocateFixed
        v-else-if="locationStore.displayLocation && locationStore.state === 'watching'"
        :size="20"
        aria-hidden="true"
      />
      <Crosshair v-else :size="20" aria-hidden="true" />
    </button>

    <button
      class="map-tool-button"
      type="button"
      :aria-label="mapStore.camerasVisible ? '隐藏摄像头图层' : '显示摄像头图层'"
      :title="mapStore.camerasVisible ? '隐藏摄像头图层' : '显示摄像头图层'"
      :aria-pressed="mapStore.camerasVisible"
      data-testid="camera-toggle"
      @click="mapStore.toggleCameras"
    >
      <Layers3 v-if="mapStore.camerasVisible" :size="20" aria-hidden="true" />
      <EyeOff v-else :size="20" aria-hidden="true" />
      <span v-if="mapStore.camerasVisible" class="map-tool-button__count">
        {{ mapStore.visibleCameras.length }}
      </span>
    </button>

    <button
      class="map-tool-button"
      type="button"
      :aria-label="mapStore.controlledAreaVisible ? '隐藏受控区图层' : '显示受控区图层'"
      :title="mapStore.controlledAreaVisible ? '隐藏受控区图层' : '显示受控区图层'"
      :aria-pressed="mapStore.controlledAreaVisible"
      data-testid="controlled-area-toggle"
      @click="mapStore.toggleControlledArea"
    >
      <LandPlot v-if="mapStore.controlledAreaVisible" :size="20" aria-hidden="true" />
      <EyeOff v-else :size="20" aria-hidden="true" />
    </button>

    <button
      class="map-tool-button"
      type="button"
      :aria-label="rerouteDisabledReason || '从当前位置重新规划'"
      :title="rerouteDisabledReason || '从当前位置重新规划'"
      :disabled="Boolean(rerouteDisabledReason)"
      data-testid="reroute-button"
      @click="handleReroute"
    >
      <span
        v-if="routeStore.isPlanning && routeStore.planningKind === 'reroute'"
        class="spinner spinner--small"
      />
      <RefreshCw v-else :size="20" aria-hidden="true" />
    </button>
  </div>
</template>
