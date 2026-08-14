import { environment } from '@/config/environment';
import type { AmapDriving, AmapDrivingResult, AmapNamespace } from '@/types/amap';
import type { ExternalRoute } from '@/types/map';
import { wgs84ToGcj02 } from '@/utils/wgs84ToGcj02';
import { loadAmap } from './amapLoader';

const MOCK_ROUTES: ExternalRoute[] = [
  {
    id: 'mock-external-0',
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
    id: 'mock-external-1',
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
 * 获取界外驾车参考路线（高德 JS API AMap.Driving，返回 GCJ02）。
 * 单次 search 只返回一条推荐路线，因此并行调用多个策略（速度/费用/距离优先），
 * 合并去重后返回最多 3 条备选路线。
 */
export async function fetchAmapDrivingRoutes(
  from: { lng: number; lat: number },
  to: { lng: number; lat: number },
  signal?: AbortSignal,
): Promise<ExternalRoute[]> {
  if (environment.useMockApi) {
    await new Promise((resolve) => window.setTimeout(resolve, 120));
    if (signal?.aborted) throw new DOMException('请求已取消', 'AbortError');
    return MOCK_ROUTES;
  }
  const amap = await loadAmap(environment.amapKey, environment.amapSecurityCode);
  const fromGcj02 = wgs84ToGcj02(from.lng, from.lat);
  const toGcj02 = wgs84ToGcj02(to.lng, to.lat);
  const policies = [
    amap.DrivingPolicy?.LEAST_TIME ?? 0,
    amap.DrivingPolicy?.LEAST_FEE ?? 1,
    amap.DrivingPolicy?.LEAST_DISTANCE ?? 2,
  ];
  const settled = await Promise.allSettled(
    policies.map((policy) => searchWithPolicy(amap, policy, fromGcj02, toGcj02, signal)),
  );
  const routes = settled
    .flatMap((result) => (result.status === 'fulfilled' ? result.value : []))
    .filter(uniqueRoute);
  if (routes.length === 0) {
    throw new Error('高德路线规划未返回可用路线');
  }
  return routes.slice(0, 3);
}

function searchWithPolicy(
  amap: AmapNamespace,
  policy: number,
  from: { lng: number; lat: number },
  to: { lng: number; lat: number },
  signal?: AbortSignal,
): Promise<ExternalRoute[]> {
  const driving: AmapDriving = new amap.Driving({ policy });
  return new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('请求已取消', 'AbortError'));
    signal?.addEventListener('abort', abort, { once: true });
    driving.search(
      [from.lng, from.lat],
      [to.lng, to.lat],
      (status: string, result: AmapDrivingResult) => {
        signal?.removeEventListener('abort', abort);
        if (status !== 'complete' || !Array.isArray(result?.routes)) {
          reject(new Error('高德路线规划未返回可用路线'));
          return;
        }
        const routes = result.routes
          .map((route, index) => ({
            id: `amap-${policy}-${index}`,
            distanceMeters: route.distance,
            durationSeconds: route.time,
            geometry: (route.steps ?? [])
              .flatMap((step) => step.path ?? [])
              .map((point) => ({ lng: point.getLng(), lat: point.getLat() })),
          }))
          .filter((route) => route.geometry.length >= 2);
        if (routes.length === 0) {
          reject(new Error('高德路线规划未返回可用路线'));
          return;
        }
        resolve(routes.slice(0, 1));
      },
    );
  });
}

function uniqueRoute(route: ExternalRoute, index: number, routes: ExternalRoute[]): boolean {
  return routes.findIndex((other) => sameRoute(other, route)) === index;
}

function sameRoute(first: ExternalRoute, second: ExternalRoute): boolean {
  const firstPoint = first.geometry[0];
  const lastPoint = first.geometry[first.geometry.length - 1];
  const secondPoint = second.geometry[0];
  const secondLast = second.geometry[second.geometry.length - 1];
  const distanceBand = Math.round(first.distanceMeters / 200);
  return (
    distanceBand === Math.round(second.distanceMeters / 200) &&
    firstPoint !== undefined &&
    secondPoint !== undefined &&
    lastPoint !== undefined &&
    secondLast !== undefined &&
    Math.abs(firstPoint.lng - secondPoint.lng) < 1e-4 &&
    Math.abs(firstPoint.lat - secondPoint.lat) < 1e-4 &&
    Math.abs(lastPoint.lng - secondLast.lng) < 1e-4 &&
    Math.abs(lastPoint.lat - secondLast.lat) < 1e-4
  );
}
