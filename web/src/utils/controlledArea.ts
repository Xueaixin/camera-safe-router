import type { ControlledArea, OutputCoordinate } from '@/types/api';

/** 判断 GCJ02 点是否位于受控区多边形内（含内环洞）。 */
export function isPointInsideControlledArea(
  point: { lng: number; lat: number },
  area: ControlledArea,
): boolean {
  return area.geometry.coordinates.some((polygon) => {
    const outer = polygon[0];
    if (!outer || !pointInRing(point, outer)) return false;
    for (let index = 1; index < polygon.length; index += 1) {
      const hole = polygon[index];
      if (hole && pointInRing(point, hole)) return false;
    }
    return true;
  });
}

function pointInRing(point: { lng: number; lat: number }, ring: OutputCoordinate[]): boolean {
  let inside = false;
  for (let index = 0, previous = ring.length - 1; index < ring.length; previous = index++) {
    const current = ring[index]!;
    const last = ring[previous]!;
    const intersects =
      current.lat > point.lat !== last.lat > point.lat &&
      point.lng <
        ((last.lng - current.lng) * (point.lat - current.lat)) / (last.lat - current.lat) +
          current.lng;
    if (intersects) inside = !inside;
  }
  return inside;
}
