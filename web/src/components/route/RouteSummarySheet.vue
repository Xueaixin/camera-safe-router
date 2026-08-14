<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock3,
  MapPinned,
  RefreshCw,
  Route,
  ShieldCheck,
} from '@lucide/vue';

import RouteSteps from './RouteSteps.vue';
import { LOCATION_POLICY } from '@/config/locationPolicy';
import { useLocationStore } from '@/stores/locationStore';
import { useMapStore } from '@/stores/mapStore';
import { useRouteStore } from '@/stores/routeStore';
import { buildAmapPointNavigationUri } from '@/maps/amapUri';
import type { SelectedPlace } from '@/types/coordinate';
import { isPointInsideControlledArea } from '@/utils/controlledArea';
import { formatDistance, formatDuration, formatSnapshotVersion } from '@/utils/format';
import { wgs84ToGcj02 } from '@/utils/wgs84ToGcj02';

const routeStore = useRouteStore();
const locationStore = useLocationStore();
const mapStore = useMapStore();
const expanded = ref(false);
const showSteps = ref(false);

watch(
  () => routeStore.route?.routeId,
  (routeId) => {
    if (routeId) expanded.value = true;
  },
);

watch(
  () => routeStore.routeView,
  (view) => {
    if (view === 'safe-segment') showSteps.value = false;
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

function retry() {
  if (routeStore.lastAttemptKind === 'reroute') {
    // 重新规划已移至地图侧边工具栏，底部仅保留失败重试入口。
    if (locationStore.browserLocation)
      void routeStore.rerouteFromLocation(locationStore.browserLocation);
  } else void routeStore.plan();
}

function gcj02ForPlace(place: SelectedPlace | null): { lng: number; lat: number } | null {
  if (!place) return null;
  const coordinate = place.coordinate;
  return coordinate.coordinateSystem === 'GCJ02'
    ? { lng: coordinate.lng, lat: coordinate.lat }
    : wgs84ToGcj02(coordinate.lng, coordinate.lat);
}

function navigationUriFromEndpoints(): string | null {
  const from = gcj02ForPlace(routeStore.start);
  const to = gcj02ForPlace(routeStore.end);
  if (!from || !to) return null;
  return buildAmapPointNavigationUri(
    from,
    routeStore.start?.name || '起点',
    to,
    routeStore.end?.name || '终点',
  );
}

function externalNavigationUri(): string | null {
  const route = routeStore.route;
  if (!route || route.planningMode !== 'EXTERNAL_ONLY') return null;
  return navigationUriFromEndpoints();
}

const externalNavigationUrl = computed(() => externalNavigationUri());

const outsideBoundsInScenarioOne = computed(() => {
  if (routeStore.error?.state !== 'outside-bounds') return false;
  if (!routeStore.start || !routeStore.end) return false;
  const area = mapStore.controlledArea;
  if (!area) return true;
  const start = gcj02ForPlace(routeStore.start);
  const end = gcj02ForPlace(routeStore.end);
  if (!start || !end) return false;
  return !isPointInsideControlledArea(start, area) && !isPointInsideControlledArea(end, area);
});

const outsideBoundsNavigationUrl = computed(() =>
  outsideBoundsInScenarioOne.value ? navigationUriFromEndpoints() : null,
);

const externalPending = computed(
  () =>
    routeStore.routeView === 'full' &&
    routeStore.externalRouteState === 'loading' &&
    routeStore.externalRoutes.length === 0,
);
const displayTimeText = computed(() =>
  externalPending.value ? '计算中…' : formatDuration(routeStore.displayDurationSeconds),
);
const displayDistanceText = computed(() =>
  externalPending.value ? '计算中…' : formatDistance(routeStore.displayDistanceMeters),
);
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
            <span>{{ displayTimeText }}</span>
          </div>
          <div>
            <MapPinned :size="17" aria-hidden="true" />
            <span>{{ displayDistanceText }}</span>
          </div>
          <div v-if="routeStore.route.planningMode !== 'EXTERNAL_ONLY'" class="route-metrics__safe">
            <ShieldCheck :size="17" aria-hidden="true" />
            <span>冲突 0</span>
          </div>
        </div>
        <div
          v-if="routeStore.isCrossBoundary"
          class="route-view-switch"
          role="group"
          aria-label="路线显示范围"
        >
          <button
            type="button"
            :class="{ 'is-active': routeStore.routeView === 'full' }"
            :aria-pressed="routeStore.routeView === 'full'"
            data-testid="show-full-route"
            @click="routeStore.showFullRoute"
          >
            完整行程
          </button>
          <button
            type="button"
            :class="{ 'is-active': routeStore.routeView === 'safe-segment' }"
            :aria-pressed="routeStore.routeView === 'safe-segment'"
            data-testid="show-safe-route"
            @click="routeStore.showSafeSegment"
          >
            环内行程
          </button>
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
        class="feedback"
        :class="[
          outsideBoundsInScenarioOne ? 'feedback--warning' : 'feedback--error',
          { 'feedback--retained': routeStore.route },
        ]"
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
      <a
        v-if="outsideBoundsNavigationUrl"
        class="button button--secondary button--compact route-external__navigate"
        :href="outsideBoundsNavigationUrl"
        target="_blank"
        rel="noopener noreferrer"
        data-testid="outside-bounds-navigation"
      >
        高德导航
      </a>

      <div v-if="routeStore.route" class="route-details">
        <div class="route-status">
          <CheckCircle2 :size="18" aria-hidden="true" />
          <span>
            <strong>
              {{
                routeStore.route.planningMode === 'EXTERNAL_ONLY'
                  ? '界外路线规划完成'
                  : '路线已通过安全校验'
              }}
            </strong>
            <small v-if="routeStore.route.planningMode === 'EXTERNAL_ONLY'">
              界外路线由服务端路网规划，仅供参考；实际导航请以高德导航为准。
            </small>
            <small v-else>
              点位快照 {{ formatSnapshotVersion(routeStore.route.cameraSnapshotVersion) }}
            </small>
          </span>
        </div>

        <div
          v-if="
            routeStore.isCrossBoundary &&
              routeStore.externalRoutes.length > 0 &&
              routeStore.routeView === 'full'
          "
          class="route-external"
          data-testid="external-route-summary"
        >
          <div class="route-external__label">界外参考路线</div>
          <div class="route-external__tabs" role="tablist" aria-label="界外备选路线">
            <button
              v-for="(externalRoute, index) in routeStore.externalRoutes"
              :key="externalRoute.id"
              type="button"
              role="tab"
              :aria-selected="index === routeStore.selectedExternalRoute"
              :class="{ 'is-active': index === routeStore.selectedExternalRoute }"
              :data-testid="`external-route-tab-${index}`"
              @click="routeStore.selectExternalRoute(index)"
            >
              <span class="route-external__tab-distance">
                {{ formatDistance(externalRoute.distanceMeters) }}
              </span>
              <span class="route-external__tab-duration">
                {{ formatDuration(externalRoute.durationSeconds) }}
              </span>
            </button>
          </div>
          <small class="route-external__note">界外路线规划由高德导航提供</small>
        </div>
        <div
          v-else-if="
            routeStore.isCrossBoundary &&
              routeStore.routeView === 'full' &&
              routeStore.externalRouteState === 'loading'
          "
          class="route-external"
          data-testid="external-route-loading"
        >
          正在加载界外参考路线…
        </div>
        <div
          v-else-if="
            routeStore.isCrossBoundary &&
              routeStore.routeView === 'full' &&
              routeStore.externalRouteState === 'error'
          "
          class="route-external route-external--error"
          data-testid="external-route-error"
        >
          界外参考路线加载失败：{{ routeStore.externalRouteError }}
        </div>
        <a
          v-if="externalNavigationUrl"
          class="button button--secondary button--compact route-external__navigate"
          :href="externalNavigationUrl"
          target="_blank"
          rel="noopener noreferrer"
          data-testid="external-only-navigation"
        >
          高德导航
        </a>

        <button
          v-if="routeStore.routeView === 'full' && routeStore.route.steps.length"
          class="steps-toggle"
          type="button"
          :aria-expanded="showSteps"
          @click="showSteps = !showSteps"
        >
          道路步骤
          <ChevronDown v-if="showSteps" :size="17" aria-hidden="true" />
          <ChevronUp v-else :size="17" aria-hidden="true" />
        </button>
        <RouteSteps
          v-if="routeStore.routeView === 'full' && showSteps"
          :steps="routeStore.route.steps"
        />
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
    </div>
  </section>
</template>
