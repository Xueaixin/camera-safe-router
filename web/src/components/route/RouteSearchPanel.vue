<script setup lang="ts">
import { ref, watch } from 'vue';
import { ArrowUpDown, ChevronDown, Route } from '@lucide/vue';

import PlaceInput from './PlaceInput.vue';
import { useRouteStore } from '@/stores/routeStore';
import type { SelectedPlace } from '@/types/coordinate';
import type { PlaceSuggestion } from '@/types/map';

defineProps<{
  searchPlaces: (keyword: string, signal?: AbortSignal) => Promise<PlaceSuggestion[]>;
  currentLocationStatus?: string | null;
}>();

const emit = defineEmits<{ useCurrent: [] }>();
const routeStore = useRouteStore();
const collapsed = ref(false);

function toggleCollapsed() {
  collapsed.value = !collapsed.value;
}

function isNarrowLayout(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 899px)').matches;
}

// 地图选点或“从当前位置重新规划”会更新起点/终点，此时自动展开搜索面板。
watch(
  () => [routeStore.start, routeStore.end] as const,
  () => {
    if (isNarrowLayout()) collapsed.value = false;
  },
);

// 窄屏（移动端）规划成功后自动收起搜索面板；宽屏保持与底部面板连接，不收起。
watch(
  () => routeStore.state,
  (state) => {
    if (state === 'success' && isNarrowLayout()) collapsed.value = true;
  },
);

function selectStart(place: SelectedPlace) {
  routeStore.setStart(place);
}

function selectEnd(place: SelectedPlace) {
  routeStore.setEnd(place);
}
</script>

<template>
  <section class="search-panel" :class="{ 'is-collapsed': collapsed }" aria-label="路线起终点">
    <button
      class="search-panel__toggle"
      type="button"
      :aria-label="collapsed ? '展开搜索面板' : '收起搜索面板'"
      :aria-expanded="!collapsed"
      data-testid="search-panel-toggle"
      @click="toggleCollapsed"
    >
      <template v-if="collapsed">
        <span class="search-panel__summary">
          <span class="search-panel__mini">
            <i aria-hidden="true">起</i>
            <span>{{ routeStore.start?.name ?? '未设置' }}</span>
          </span>
          <span class="search-panel__arrow" aria-hidden="true">→</span>
          <span class="search-panel__mini">
            <i aria-hidden="true">终</i>
            <span>{{ routeStore.end?.name ?? '未设置' }}</span>
          </span>
        </span>
        <ChevronDown :size="18" aria-hidden="true" />
      </template>
      <template v-else>
        <span class="search-panel__grip" aria-hidden="true" />
      </template>
    </button>

    <div class="search-panel__body" :class="{ 'is-hidden': collapsed }">
      <div class="endpoint-fields">
        <PlaceInput
          id="route-start"
          label="搜索起点"
          kind="start"
          :place="routeStore.start"
          :search-places="searchPlaces"
          :current-status="currentLocationStatus ?? null"
          allow-current
          @select="selectStart"
          @clear="routeStore.setStart(null)"
          @use-current="emit('useCurrent')"
        />

        <button
          class="icon-button endpoint-fields__swap"
          type="button"
          aria-label="交换起点和终点"
          title="交换起终点"
          data-testid="swap-endpoints"
          :disabled="!routeStore.start && !routeStore.end"
          @click="routeStore.swapEndpoints"
        >
          <ArrowUpDown :size="18" aria-hidden="true" />
        </button>

        <PlaceInput
          id="route-end"
          label="搜索终点"
          kind="end"
          :place="routeStore.end"
          :search-places="searchPlaces"
          @select="selectEnd"
          @clear="routeStore.setEnd(null)"
        />
      </div>

      <button
        class="button button--primary plan-button"
        type="button"
        :disabled="!routeStore.canPlan"
        data-testid="plan-route"
        @click="routeStore.plan()"
      >
        <span
          v-if="routeStore.isPlanning && routeStore.planningKind === 'initial'"
          class="spinner"
        />
        <Route v-else :size="18" aria-hidden="true" />
        {{
          routeStore.isPlanning && routeStore.planningKind === 'initial' ? '正在规划' : '规划路线'
        }}
      </button>
    </div>
  </section>
</template>
