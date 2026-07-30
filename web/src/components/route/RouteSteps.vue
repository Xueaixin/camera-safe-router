<script setup lang="ts">
import { CornerDownRight } from '@lucide/vue';

import type { RouteStep } from '@/types/api';
import { formatDistance, formatDuration } from '@/utils/format';

defineProps<{ steps: RouteStep[] }>();
</script>

<template>
  <ol v-if="steps.length" class="route-steps">
    <li v-for="(step, index) in steps" :key="`${step.startIndex}-${step.endIndex}-${index}`">
      <CornerDownRight :size="17" aria-hidden="true" />
      <span>
        <strong>{{ step.instruction }}</strong>
        <small>
          {{ step.roadName || '未命名道路' }} · {{ formatDistance(step.distanceMeters) }} ·
          {{ formatDuration(step.durationSeconds) }}
        </small>
      </span>
    </li>
  </ol>
  <p v-else class="route-steps__empty">当前路线没有道路步骤详情。</p>
</template>
