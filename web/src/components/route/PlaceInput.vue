<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { MapPin, Navigation, X } from '@lucide/vue';

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
  useCurrent: [];
}>();

const query = ref(props.place?.name ?? '');
const suggestions = ref<PlaceSuggestion[]>([]);
const searchState = ref<'idle' | 'loading' | 'error'>('idle');
const activeIndex = ref(-1);
const input = ref<HTMLInputElement | null>(null);
const isFocused = ref(false);
const currentOptionOffset = computed(() => (props.allowCurrent ? 1 : 0));
const optionCount = computed(() => suggestions.value.length + currentOptionOffset.value);
const isOpen = computed(
  () =>
    (Boolean(props.allowCurrent) && isFocused.value) ||
    suggestions.value.length > 0 ||
    searchState.value === 'error',
);
let timer: number | null = null;
let closeTimer: number | null = null;
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
  closeDropdown();
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

function chooseCurrent() {
  closeDropdown();
  emit('useCurrent');
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && isOpen.value) {
    event.preventDefault();
    closeDropdown();
  } else if (event.key === 'ArrowDown' && optionCount.value > 0) {
    event.preventDefault();
    activeIndex.value = (activeIndex.value + 1) % optionCount.value;
  } else if (event.key === 'ArrowUp' && optionCount.value > 0) {
    event.preventDefault();
    activeIndex.value = (activeIndex.value - 1 + optionCount.value) % optionCount.value;
  } else if (event.key === 'Enter' && activeIndex.value >= 0) {
    event.preventDefault();
    if (props.allowCurrent && activeIndex.value === 0) {
      chooseCurrent();
      return;
    }
    const suggestion = suggestions.value[activeIndex.value - currentOptionOffset.value];
    if (suggestion) choose(suggestion);
  }
}

function handleFocus() {
  if (closeTimer !== null) window.clearTimeout(closeTimer);
  isFocused.value = true;
}

function closeDropdown() {
  if (timer !== null) window.clearTimeout(timer);
  if (closeTimer !== null) window.clearTimeout(closeTimer);
  timer = null;
  closeTimer = null;
  controller?.abort();
  controller = null;
  suggestions.value = [];
  searchState.value = 'idle';
  activeIndex.value = -1;
  isFocused.value = false;
  input.value?.blur();
}

function delayedClose() {
  closeTimer = window.setTimeout(() => {
    closeDropdown();
  }, 160);
}

onBeforeUnmount(() => {
  if (timer !== null) window.clearTimeout(timer);
  if (closeTimer !== null) window.clearTimeout(closeTimer);
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
        ref="input"
        v-model="query"
        :placeholder="label"
        :aria-expanded="isOpen"
        :aria-controls="`${id}-suggestions`"
        :aria-activedescendant="activeIndex >= 0 ? `${id}-option-${activeIndex}` : undefined"
        autocomplete="off"
        role="combobox"
        @input="handleInput"
        @focus="handleFocus"
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
    </div>

    <div
      v-if="isOpen"
      :id="`${id}-suggestions`"
      class="suggestions"
      role="listbox"
      :aria-label="`${label}搜索结果`"
    >
      <button
        v-if="allowCurrent"
        :id="`${id}-option-0`"
        type="button"
        role="option"
        :aria-selected="activeIndex === 0"
        :class="[
          'suggestions__item',
          'suggestions__item--current',
          { 'is-active': activeIndex === 0 },
        ]"
        data-testid="use-current-option"
        @click="chooseCurrent"
      >
        <Navigation :size="16" aria-hidden="true" />
        <span><strong>使用当前位置</strong></span>
      </button>
      <p v-if="searchState === 'error'" class="suggestions__state" role="alert">
        地址搜索失败，请检查地图连接后重试。
      </p>
      <button
        v-for="(suggestion, index) in suggestions"
        :id="`${id}-option-${index + currentOptionOffset}`"
        :key="suggestion.id"
        type="button"
        role="option"
        :aria-selected="index + currentOptionOffset === activeIndex"
        :class="['suggestions__item', { 'is-active': index + currentOptionOffset === activeIndex }]"
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
