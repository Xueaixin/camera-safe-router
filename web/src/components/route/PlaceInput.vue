<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Crosshair, MapPin, Navigation, X } from '@lucide/vue';

import type { SelectedPlace } from '@/types/coordinate';
import type { PlaceSuggestion } from '@/types/map';

const props = defineProps<{
  id: string;
  label: string;
  kind: 'start' | 'end';
  place: SelectedPlace | null;
  allowCurrent?: boolean;
  searchPlaces: (keyword: string, signal?: AbortSignal) => Promise<PlaceSuggestion[]>;
}>();

const emit = defineEmits<{
  select: [place: SelectedPlace];
  clear: [];
  mapPick: [];
  useCurrent: [];
}>();

const query = ref(props.place?.name ?? '');
const suggestions = ref<PlaceSuggestion[]>([]);
const searchState = ref<'idle' | 'loading' | 'error'>('idle');
const activeIndex = ref(-1);
const isOpen = computed(() => suggestions.value.length > 0 || searchState.value === 'error');
let timer: number | null = null;
let controller: AbortController | null = null;

watch(
  () => props.place,
  (place) => {
    query.value = place?.name ?? '';
    suggestions.value = [];
  },
);

function handleInput() {
  if (props.place && query.value !== props.place.name) emit('clear');
  if (timer !== null) window.clearTimeout(timer);
  controller?.abort();
  suggestions.value = [];
  activeIndex.value = -1;
  const keyword = query.value.trim();
  if (!keyword) {
    searchState.value = 'idle';
    return;
  }
  timer = window.setTimeout(() => void search(keyword), 220);
}

async function search(keyword: string) {
  controller = new AbortController();
  searchState.value = 'loading';
  try {
    suggestions.value = await props.searchPlaces(keyword, controller.signal);
    searchState.value = 'idle';
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
    searchState.value = 'error';
  }
}

function choose(suggestion: PlaceSuggestion) {
  query.value = suggestion.name;
  suggestions.value = [];
  activeIndex.value = -1;
  emit('select', {
    name: suggestion.name,
    coordinate: suggestion.coordinate,
    source: 'AMAP_SEARCH',
  });
}

function clear() {
  query.value = '';
  suggestions.value = [];
  searchState.value = 'idle';
  emit('clear');
}

function handleKeydown(event: KeyboardEvent) {
  if (!suggestions.value.length) return;
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    activeIndex.value = (activeIndex.value + 1) % suggestions.value.length;
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    activeIndex.value =
      (activeIndex.value - 1 + suggestions.value.length) % suggestions.value.length;
  } else if (event.key === 'Enter' && activeIndex.value >= 0) {
    event.preventDefault();
    const suggestion = suggestions.value[activeIndex.value];
    if (suggestion) choose(suggestion);
  } else if (event.key === 'Escape') {
    suggestions.value = [];
  }
}

function delayedClose() {
  window.setTimeout(() => {
    suggestions.value = [];
  }, 160);
}

onBeforeUnmount(() => {
  if (timer !== null) window.clearTimeout(timer);
  controller?.abort();
});
</script>

<template>
  <div class="place-input" :class="`place-input--${kind}`">
    <label :for="id" class="sr-only">{{ label }}</label>
    <div class="place-input__field">
      <span class="place-input__marker" aria-hidden="true">
        <Navigation v-if="kind === 'start'" :size="15" />
        <MapPin v-else :size="15" />
      </span>
      <input
        :id="id"
        v-model="query"
        :placeholder="label"
        :aria-expanded="isOpen"
        :aria-controls="`${id}-suggestions`"
        :aria-activedescendant="activeIndex >= 0 ? `${id}-option-${activeIndex}` : undefined"
        autocomplete="off"
        role="combobox"
        @input="handleInput"
        @keydown="handleKeydown"
        @blur="delayedClose"
      />
      <span v-if="searchState === 'loading'" class="spinner spinner--small" aria-label="正在搜索" />
      <button
        v-else-if="query"
        class="icon-button icon-button--small"
        type="button"
        :aria-label="`清空${label}`"
        :title="`清空${label}`"
        @click="clear"
      >
        <X :size="16" aria-hidden="true" />
      </button>
      <button
        class="icon-button icon-button--small"
        type="button"
        :aria-label="`在地图上选择${label}`"
        :title="`地图选择${label}`"
        @click="$emit('mapPick')"
      >
        <Crosshair :size="17" aria-hidden="true" />
      </button>
    </div>

    <div v-if="allowCurrent" class="place-input__quick-actions">
      <button type="button" class="text-action" @click="$emit('useCurrent')">
        <Navigation :size="14" aria-hidden="true" />
        使用当前位置
      </button>
    </div>

    <div
      v-if="isOpen"
      :id="`${id}-suggestions`"
      class="suggestions"
      role="listbox"
      :aria-label="`${label}搜索结果`"
    >
      <p v-if="searchState === 'error'" class="suggestions__state" role="alert">
        地址搜索失败，请检查地图连接后重试。
      </p>
      <button
        v-for="(suggestion, index) in suggestions"
        :id="`${id}-option-${index}`"
        :key="suggestion.id"
        type="button"
        role="option"
        :aria-selected="index === activeIndex"
        :class="['suggestions__item', { 'is-active': index === activeIndex }]"
        @mousedown.prevent="choose(suggestion)"
      >
        <MapPin :size="16" aria-hidden="true" />
        <span>
          <strong>{{ suggestion.name }}</strong>
          <small>{{ suggestion.district }}</small>
        </span>
      </button>
    </div>
  </div>
</template>
