import type { AmapNamespace } from '@/types/amap';

const SCRIPT_ID = 'amap-jsapi-v2';
let loadPromise: Promise<AmapNamespace> | null = null;

export function loadAmap(key: string, securityCode: string): Promise<AmapNamespace> {
  if (!key || !securityCode) {
    return Promise.reject(new Error('缺少高德地图 Key 或安全密钥'));
  }
  window._AMapSecurityConfig = { securityJsCode: securityCode };
  if (window.AMap) return Promise.resolve(window.AMap);
  if (loadPromise) return loadPromise;

  loadPromise = new Promise((resolve, reject) => {
    const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    const script = existing ?? document.createElement('script');
    const fail = (message: string) => {
      loadPromise = null;
      script.remove();
      reject(new Error(message));
    };
    const timeout = window.setTimeout(() => fail('高德地图加载超时'), 15_000);
    script.addEventListener(
      'load',
      () => {
        window.clearTimeout(timeout);
        if (!window.AMap) {
          fail('高德地图脚本已加载但 SDK 不可用');
          return;
        }
        resolve(window.AMap);
      },
      { once: true },
    );
    script.addEventListener(
      'error',
      () => {
        window.clearTimeout(timeout);
        fail('高德地图加载失败');
      },
      { once: true },
    );
    if (!existing) {
      script.id = SCRIPT_ID;
      script.async = true;
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.AutoComplete,AMap.Geocoder`;
      document.head.appendChild(script);
    }
  });
  return loadPromise;
}

export function resetAmapLoader() {
  if (!window.AMap) document.getElementById(SCRIPT_ID)?.remove();
  loadPromise = null;
}
