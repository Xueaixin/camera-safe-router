import type { HandoffMarkerData } from '@/types/map';

function navigationPoint(coordinate: { lng: number; lat: number }, name: string): string {
  return `${coordinate.lng},${coordinate.lat},${name}`;
}

export function buildAmapNavigationUri(data: HandoffMarkerData): string {
  const outbound = data.crossing.direction === 'OUTBOUND';
  const params = new URLSearchParams({
    from: navigationPoint(
      outbound ? data.navigationHandoff.gcj02 : data.outerEndpoint,
      outbound ? '环内路线交接点' : '环外行程起点',
    ),
    to: navigationPoint(
      outbound ? data.outerEndpoint : data.navigationHandoff.gcj02,
      outbound ? '环外行程终点' : '环内路线交接点',
    ),
    mode: 'car',
    policy: '0',
    src: 'camera-safe-routing',
    callnative: '1',
  });
  return `https://uri.amap.com/navigation?${params.toString()}`;
}

export function buildAmapPointNavigationUri(
  from: { lng: number; lat: number },
  fromName: string,
  to: { lng: number; lat: number },
  toName: string,
): string {
  const params = new URLSearchParams({
    from: navigationPoint(from, fromName),
    to: navigationPoint(to, toName),
    mode: 'car',
    policy: '0',
    src: 'camera-safe-routing',
    callnative: '1',
  });
  return `https://uri.amap.com/navigation?${params.toString()}`;
}
