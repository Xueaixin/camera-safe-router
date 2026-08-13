import { wgs84LineToGcj02, type Gcj02Point } from '@/utils/wgs84ToGcj02';
import { environment } from '@/config/environment';

export interface OsrmRoute {
  distanceMeters: number;
  durationSeconds: number;
  geometry: Gcj02Point[];
}

interface OsrmRoutePayload {
  distanceMeters: number;
  durationSeconds: number;
  geometry: Array<[number, number]>;
}

const MOCK_ROUTES: OsrmRoute[] = [
  {
    distanceMeters: 86_600,
    durationSeconds: 4_920,
    geometry: [
      { lng: 116.46, lat: 39.935 },
      { lng: 116.75, lat: 39.88 },
      { lng: 117.05, lat: 39.6 },
      { lng: 117.21, lat: 39.136 },
    ],
  },
  {
    distanceMeters: 91_200,
    durationSeconds: 5_340,
    geometry: [
      { lng: 116.46, lat: 39.935 },
      { lng: 116.9, lat: 39.92 },
      { lng: 117.18, lat: 39.5 },
      { lng: 117.21, lat: 39.136 },
    ],
  },
];

/**
 * 获取界外驾车参考路线（WGS84 输入，GCJ02 输出）。
 * 真实模式走后端代理（同源、免费 OSRM）；mock 模式返回固定数据。
 */
export async function fetchOsrmRoutes(
  from: { lng: number; lat: number },
  to: { lng: number; lat: number },
  signal?: AbortSignal,
): Promise<OsrmRoute[]> {
  if (environment.useMockApi) {
    await new Promise((resolve) => window.setTimeout(resolve, 120));
    if (signal?.aborted) throw new DOMException('请求已取消', 'AbortError');
    return MOCK_ROUTES;
  }
  const url =
    `${environment.apiBaseUrl}/api/v1/external-route` +
    `?fromLng=${from.lng}&fromLat=${from.lat}&toLng=${to.lng}&toLat=${to.lat}`;
  const response = await fetch(url, signal ? { signal } : undefined);
  if (!response.ok) {
    throw new Error(`界外路线请求失败：HTTP ${response.status}`);
  }
  const payload = (await response.json()) as {
    code: string;
    routes: OsrmRoutePayload[];
  };
  if (payload.code !== 'Ok' || !Array.isArray(payload.routes)) {
    throw new Error(`界外路线返回异常：${payload.code ?? 'unknown'}`);
  }
  return payload.routes
    .filter((route) => Array.isArray(route.geometry) && route.geometry.length >= 2)
    .map((route) => ({
      distanceMeters: route.distanceMeters,
      durationSeconds: Math.round(route.durationSeconds),
      geometry: wgs84LineToGcj02(route.geometry.map(([lng, lat]) => ({ lng, lat }))),
    }));
}
