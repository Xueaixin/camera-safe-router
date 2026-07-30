import { afterEach, describe, expect, it } from 'vitest';

import { loadAmap, resetAmapLoader } from '@/maps/amapLoader';

describe('AMap loader', () => {
  afterEach(() => {
    resetAmapLoader();
    delete window.AMap;
    delete window._AMapSecurityConfig;
  });

  it('sets security configuration first and injects the SDK script only once', async () => {
    const first = loadAmap('test-key', 'test-security-code');
    const second = loadAmap('test-key', 'test-security-code');

    expect(first).toBe(second);
    expect(window._AMapSecurityConfig).toEqual({ securityJsCode: 'test-security-code' });
    const scripts = document.querySelectorAll('#amap-jsapi-v2');
    expect(scripts).toHaveLength(1);
    expect((scripts[0] as HTMLScriptElement).src).toContain('key=test-key');

    scripts[0]?.dispatchEvent(new Event('error'));
    const results = await Promise.allSettled([first, second]);
    expect(results.every((result) => result.status === 'rejected')).toBe(true);
  });
});
