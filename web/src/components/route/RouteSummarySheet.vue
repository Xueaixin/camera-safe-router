<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock3,
  LocateFixed,
  MapPinned,
  RefreshCw,
  Route,
  ShieldCheck,
} from '@lucide/vue';

import RouteSteps from './RouteSteps.vue';
import { LOCATION_POLICY } from '@/config/locationPolicy';
import { useLocationStore } from '@/stores/locationStore';
import { useRouteStore } from '@/stores/routeStore';
import { formatDistance, formatDuration, formatSnapshotVersion } from '@/utils/format';

const routeStore = useRouteStore();
const locationStore = useLocationStore();
const expanded = ref(false);
const showSteps = ref(false);

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

watch(
  () => routeStore.route?.routeId,
  (routeId) => {
    if (routeId) expanded.value = true;
  },
);

watch(
  () => routeStore.state,
  (state) => {
    if (state !== 'idle' && state !== 'planning' && state !== 'success') expanded.value = true;
  },
);

watch(
  () => locationStore.state,
  (state) => {
    if (state === 'permission-denied' || state === 'timeout' || state === 'unavailable') {
      expanded.value = true;
    }
  },
);

watch(
  () => [locationStore.browserLocation, locationStore.isFresh, locationStore.isAccurate] as const,
  ([location, fresh, accurate]) => {
    if (location && (!fresh || !accurate)) expanded.value = true;
  },
);

function reroute() {
  if (rerouteDisabledReason.value || !locationStore.browserLocation) return;
  void routeStore.rerouteFromLocation(locationStore.browserLocation);
}

function retry() {
  if (routeStore.lastAttemptKind === 'reroute') reroute();
  else void routeStore.plan();
}
</script>

<template>
  <section
    class="route-sheet"
    :class="{ 'is-expanded': expanded }"
    aria-label="路线摘要"
    data-testid="route-summary"
  >
    <button
      class="route-sheet__handle"
      type="button"
      :aria-label="expanded ? '收起路线详情' : '展开路线详情'"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span aria-hidden="true" />
    </button>

    <div class="route-sheet__summary" aria-live="polite">
      <template v-if="routeStore.route">
        <div class="route-metrics">
          <div>
            <Clock3 :size="17" aria-hidden="true" />
            <span>{{ formatDuration(routeStore.route.durationSeconds) }}</span>
          </div>
          <div>
            <MapPinned :size="17" aria-hidden="true" />
            <span>{{ formatDistance(routeStore.route.distanceMeters) }}</span>
          </div>
          <div class="route-metrics__safe">
            <ShieldCheck :size="17" aria-hidden="true" />
            <span>冲突 0</span>
          </div>
        </div>
      </template>
      <div v-else-if="routeStore.state === 'planning'" class="route-empty">
        <span class="spinner" aria-hidden="true" />
        <span>正在计算合规路线</span>
      </div>
      <div v-else class="route-empty">
        <Route :size="19" aria-hidden="true" />
        <span>等待规划</span>
      </div>
    </div>

    <div class="route-sheet__body">
      <div
        v-if="routeStore.error"
        class="feedback feedback--error"
        :class="{ 'feedback--retained': routeStore.route }"
        role="alert"
        data-testid="route-error"
      >
        <AlertCircle :size="19" aria-hidden="true" />
        <span>
          <strong>{{ routeStore.error.title }}</strong>
          <small>{{ routeStore.error.message }}</small>
          <small v-if="routeStore.route">原路线仍保留，尚未从当前位置重新计算。</small>
        </span>
        <button
          v-if="routeStore.error.retryable"
          class="button button--secondary button--compact"
          type="button"
          @click="retry"
        >
          <RefreshCw :size="15" aria-hidden="true" />
          重试
        </button>
      </div>

      <div v-if="routeStore.route" class="route-details">
        <div class="route-status">
          <CheckCircle2 :size="18" aria-hidden="true" />
          <span>
            <strong>路线已通过安全校验</strong>
            <small>
              点位快照 {{ formatSnapshotVersion(routeStore.route.cameraSnapshotVersion) }}
            </small>
          </span>
        </div>

        <button
          v-if="routeStore.route.steps.length"
          class="steps-toggle"
          type="button"
          :aria-expanded="showSteps"
          @click="showSteps = !showSteps"
        >
          道路步骤
          <ChevronDown v-if="showSteps" :size="17" aria-hidden="true" />
          <ChevronUp v-else :size="17" aria-hidden="true" />
        </button>
        <RouteSteps v-if="showSteps" :steps="routeStore.route.steps" />
      </div>

      <div v-if="locationStore.state === 'permission-denied'" class="feedback" role="status">
        <AlertCircle :size="18" aria-hidden="true" />
        <span>定位权限被拒绝。请在浏览器站点设置中允许位置访问后重试。</span>
      </div>
      <div v-else-if="locationStore.state === 'timeout'" class="feedback" role="status">
        <AlertCircle :size="18" aria-hidden="true" />
        <span>定位超时，请移至信号较好的位置后重试。</span>
      </div>
      <div v-else-if="locationStore.state === 'unavailable'" class="feedback" role="status">
        <AlertCircle :size="18" aria-hidden="true" />
        <span>浏览器暂时无法提供位置，请检查系统定位服务后重试。</span>
      </div>
      <div v-else-if="locationStore.conversionError" class="feedback" role="status">
        <AlertCircle :size="18" aria-hidden="true" />
        <span>已取得定位，但无法转换为地图展示坐标；不会直接绘制原始位置。</span>
      </div>
      <div
        v-else-if="locationStore.browserLocation && !locationStore.isFresh"
        class="feedback"
        role="status"
      >
        <AlertCircle :size="18" aria-hidden="true" />
        <span>当前位置已超过 30 秒，请重新获取后再规划。</span>
      </div>
      <div
        v-else-if="locationStore.browserLocation && !locationStore.isAccurate"
        class="feedback"
        role="status"
      >
        <AlertCircle :size="18" aria-hidden="true" />
        <span>
          当前定位精度约
          {{ Math.round(locationStore.browserLocation.accuracyMeters) }} 米，请等待精度提升到
          {{ LOCATION_POLICY.maximumAccuracyMeters }} 米以内。
        </span>
      </div>

      <button
        class="button button--secondary reroute-button"
        type="button"
        :disabled="Boolean(rerouteDisabledReason)"
        :title="rerouteDisabledReason || '以最新当前位置为起点重新规划'"
        data-testid="reroute-button"
        @click="reroute"
      >
        <span
          v-if="routeStore.isPlanning && routeStore.planningKind === 'reroute'"
          class="spinner spinner--small"
        />
        <LocateFixed v-else :size="18" aria-hidden="true" />
        从当前位置重新规划
      </button>
    </div>
  </section>
</template>
