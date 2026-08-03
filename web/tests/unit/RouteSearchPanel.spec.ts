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
    expect(wrapper.find('[data-testid="start-map-pick"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="end-map-pick"]').exists()).toBe(false);
  });

  it('offers current location inside the start suggestions', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const wrapper = mount(RouteSearchPanel, {
      props: { searchPlaces: async () => [] },
      global: { plugins: [pinia] },
    });

    expect(wrapper.find('[data-testid="use-current-option"]').exists()).toBe(false);

    await wrapper.get('#route-start').trigger('focus');
    const currentOption = wrapper.get('[data-testid="use-current-option"]');
    expect(currentOption.attributes('role')).toBe('option');

    await currentOption.trigger('click');
    expect(wrapper.emitted('useCurrent')).toHaveLength(1);
    expect(wrapper.find('[data-testid="use-current-option"]').exists()).toBe(false);

    const startInput = wrapper.get('#route-start');
    await startInput.trigger('focus');
    await startInput.trigger('keydown', { key: 'ArrowDown' });
    expect(wrapper.get('[data-testid="use-current-option"]').classes()).toContain('is-active');
    await startInput.trigger('keydown', { key: 'Enter' });
    expect(wrapper.emitted('useCurrent')).toHaveLength(2);
    expect(wrapper.find('[data-testid="use-current-option"]').exists()).toBe(false);
  });
});
