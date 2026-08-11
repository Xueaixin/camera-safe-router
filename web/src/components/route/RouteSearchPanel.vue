<script setup lang="ts">
import { ArrowUpDown, Route } from '@lucide/vue';

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

function selectStart(place: SelectedPlace) {
  routeStore.setStart(place);
}

function selectEnd(place: SelectedPlace) {
  routeStore.setEnd(place);
}
</script>

<template>
  <section class="search-panel" aria-label="路线起终点">
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
      <span v-if="routeStore.isPlanning && routeStore.planningKind === 'initial'" class="spinner" />
      <Route v-else :size="18" aria-hidden="true" />
      {{ routeStore.isPlanning && routeStore.planningKind === 'initial' ? '正在规划' : '规划路线' }}
    </button>
  </section>
</template>
