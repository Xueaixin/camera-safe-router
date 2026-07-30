<script setup lang="ts">
import { ArrowUpDown, Route } from '@lucide/vue';

import PlaceInput from './PlaceInput.vue';
import { useMapStore } from '@/stores/mapStore';
import { useRouteStore } from '@/stores/routeStore';
import type { SelectedPlace } from '@/types/coordinate';
import type { PlaceSuggestion } from '@/types/map';

defineProps<{
  searchPlaces: (keyword: string, signal?: AbortSignal) => Promise<PlaceSuggestion[]>;
}>();

const emit = defineEmits<{ useCurrent: [] }>();
const routeStore = useRouteStore();
const mapStore = useMapStore();

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
        allow-current
        @select="selectStart"
        @clear="routeStore.setStart(null)"
        @map-pick="mapStore.beginSelection('start')"
        @use-current="emit('useCurrent')"
      />

      <button
        class="icon-button endpoint-fields__swap"
        type="button"
        aria-label="交换起点和终点"
        title="交换起终点"
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
        @map-pick="mapStore.beginSelection('end')"
      />
    </div>

    <div v-if="mapStore.selectionTarget" class="selection-notice" role="status">
      请在地图上选择{{ mapStore.selectionTarget === 'start' ? '起点' : '终点' }}
      <button type="button" class="text-action" @click="mapStore.finishSelection">取消</button>
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
