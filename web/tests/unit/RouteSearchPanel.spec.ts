import { createPinia, setActivePinia } from 'pinia';
import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';

import RouteSearchPanel from '@/components/route/RouteSearchPanel.vue';
import { useRouteStore } from '@/stores/routeStore';

describe('RouteSearchPanel', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('keeps the plan command disabled until both endpoints exist', () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const wrapper = mount(RouteSearchPanel, {
      props: { searchPlaces: async () => [] },
      global: { plugins: [pinia] },
    });
    const store = useRouteStore();
    store.setStart({
      name: '起点',
      coordinate: { lng: 116.397, lat: 39.908, coordinateSystem: 'GCJ02' },
      source: 'AMAP_SEARCH',
    });

    expect(wrapper.get('[data-testid="plan-route"]').attributes('disabled')).toBeDefined();
  });
});
