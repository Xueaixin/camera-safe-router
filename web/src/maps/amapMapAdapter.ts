import { loadAmap } from './amapLoader';
import { buildAmapNavigationUri } from './amapUri';
import {
  createCameraPopup,
  createMapPointPopup,
  createNavigationHandoffPopup,
} from './mapPopupContent';
import type {
  AmapAutoCompleteResult,
  AmapCircle,
  AmapInfoWindow,
  AmapLngLat,
  AmapMap,
  AmapMarker,
  AmapMassMarks,
  AmapNamespace,
  AmapPolyline,
  AmapPolygon,
} from '@/types/amap';
import type { CameraView, ControlledArea, OutputCoordinate } from '@/types/api';
import type { Coordinate, DisplayLocation, SelectedPlace } from '@/types/coordinate';
import type {
  HandoffMarkerData,
  MapAdapter,
  MapAdapterCallbacks,
  MapViewport,
  PlaceSuggestion,
} from '@/types/map';
import { isRecord } from '@/utils/guards';

function lngLatTuple(coordinate: { lng: number; lat: number }): [number, number] {
  return [coordinate.lng, coordinate.lat];
}

function lngLatFromEvent(event: unknown): AmapLngLat | null {
  if (!isRecord(event) || !isRecord(event.lnglat)) return null;
  const candidate = event.lnglat as Partial<AmapLngLat>;
  return typeof candidate.getLng === 'function' && typeof candidate.getLat === 'function'
    ? (candidate as AmapLngLat)
    : null;
}

function markerElement(kind: 'start' | 'end' | 'current' | 'outbound' | 'inbound'): HTMLDivElement {
  const marker = document.createElement('div');
  marker.className = `map-marker map-marker--${kind}`;
  marker.setAttribute('aria-hidden', 'true');
  if (kind !== 'current') {
    const label = document.createElement('span');
    label.textContent =
      kind === 'start' ? '起' : kind === 'end' ? '终' : kind === 'outbound' ? '出' : '入';
    marker.append(label);
  }
  return marker;
}

const PRIMARY_TRAFFIC_LANDMARK = /收费站|高速.*(?:入口|出口)|高速公路出入口|互通/;
const SECONDARY_TRAFFIC_LANDMARK = /交通设施|道路附属设施|路口|桥|立交/;

interface HandoffPoi {
  value: Record<string, unknown>;
  name: string;
  distanceMeters: number;
  priority: number;
}

function poiDistance(candidate: Record<string, unknown>): number {
  const distance =
    typeof candidate.distance === 'number'
      ? candidate.distance
      : typeof candidate.distance === 'string'
        ? Number(candidate.distance)
        : Number.NaN;
  return distance;
}

function selectHandoffPoi(candidates: unknown[], radius: number): HandoffPoi | null {
  const eligible = candidates.flatMap((candidate): HandoffPoi[] => {
    if (!isRecord(candidate) || typeof candidate.name !== 'string') return [];
    const distanceMeters = poiDistance(candidate);
    if (!Number.isFinite(distanceMeters) || distanceMeters < 0 || distanceMeters > radius) {
      return [];
    }
    const type = typeof candidate.type === 'string' ? candidate.type : '';
    const searchable = `${candidate.name} ${type}`;
    const priority = PRIMARY_TRAFFIC_LANDMARK.test(searchable)
      ? 0
      : SECONDARY_TRAFFIC_LANDMARK.test(searchable)
        ? 1
        : 2;
    return [{ value: candidate, name: candidate.name, distanceMeters, priority }];
  });
  return (
    eligible.sort(
      (left, right) => left.priority - right.priority || left.distanceMeters - right.distanceMeters,
    )[0] ?? null
  );
}

function textValues(value: unknown): string[] {
  if (typeof value === 'string' && value.trim()) return [value.trim()];
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === 'string' && Boolean(item.trim()));
}

function appendWithoutOverlap(base: string, value: string): string {
  if (!value || base.includes(value)) return base;
  if (value.includes(base)) return value;
  return `${base}${value}`;
}

function describeHandoffPoi(regeocode: Record<string, unknown>, poi: HandoffPoi): string {
  const addressComponent = isRecord(regeocode.addressComponent) ? regeocode.addressComponent : null;
  let description = '';
  if (addressComponent) {
    for (const key of ['province', 'city', 'district', 'township']) {
      for (const value of textValues(addressComponent[key])) {
        description = appendWithoutOverlap(description, value);
      }
    }
  }
  const address = typeof poi.value.address === 'string' ? poi.value.address.trim() : '';
  description = appendWithoutOverlap(description, address);
  return appendWithoutOverlap(description, poi.name);
}

export class AmapMapAdapter implements MapAdapter {
  private container: HTMLElement | null = null;
  private amap: AmapNamespace | null = null;
  private map: AmapMap | null = null;
  private callbacks: MapAdapterCallbacks | null = null;
  private startMarker: AmapMarker | null = null;
  private endMarker: AmapMarker | null = null;
  private handoffMarker: AmapMarker | null = null;
  private handoffMarkerKey: string | null = null;
  private handoffData: HandoffMarkerData | null = null;
  private handoffDescription: Promise<string> | null = null;
  private routePolyline: AmapPolyline | null = null;
  private cameraLayer: AmapMassMarks | null = null;
  private controlledAreaPolygons: AmapPolygon[] = [];
  private currentMarker: AmapMarker | null = null;
  private accuracyCircle: AmapCircle | null = null;
  private infoWindow: AmapInfoWindow | null = null;
  private infoWindowKind: 'camera' | 'map-point' | 'handoff' | null = null;
  private camerasById = new Map<string, CameraView>();
  private viewportListeners = new Set<() => void>();
  private mapClickSequence = 0;
  private suppressMapClickUntil = 0;
  private readonly mapClickHandler = (event?: unknown) => void this.handleMapClick(event);
  private readonly viewportHandler = () => this.viewportListeners.forEach((listener) => listener());
  private readonly cameraClickHandler = (event: unknown) => this.handleCameraClick(event);
  private readonly handoffClickHandler = (event: unknown) => this.handleHandoffClick(event);

  constructor(
    private readonly key: string,
    private readonly securityCode: string,
  ) {}

  async initialize(container: HTMLElement, callbacks: MapAdapterCallbacks): Promise<void> {
    this.container = container;
    this.callbacks = callbacks;
    this.amap = await loadAmap(this.key, this.securityCode);
    this.map = new this.amap.Map(container, {
      center: [116.4074, 39.9042],
      zoom: 11,
      viewMode: '2D',
      resizeEnable: true,
    });
    this.map.on('click', this.mapClickHandler);
    this.map.on('moveend', this.viewportHandler);
    this.map.on('zoomend', this.viewportHandler);
  }

  destroy() {
    this.clearRoute();
    this.clearCameras();
    this.clearControlledArea();
    this.setEndpointMarkers(null, null);
    this.setHandoffMarker(null);
    this.setCurrentLocation(null);
    this.closeInfoWindow();
    this.mapClickSequence += 1;
    if (this.map) {
      this.map.off('click', this.mapClickHandler);
      this.map.off('moveend', this.viewportHandler);
      this.map.off('zoomend', this.viewportHandler);
      this.map.destroy();
    }
    this.map = null;
    this.amap = null;
    this.container = null;
    this.callbacks = null;
    this.viewportListeners.clear();
  }

  searchPlaces(keyword: string, signal?: AbortSignal): Promise<PlaceSuggestion[]> {
    if (!this.amap) return Promise.reject(new Error('地图尚未加载'));
    const autocomplete = new this.amap.AutoComplete({ citylimit: false });
    return new Promise((resolve, reject) => {
      if (signal?.aborted) {
        reject(new DOMException('搜索已取消', 'AbortError'));
        return;
      }
      autocomplete.search(keyword, (status, result) => {
        if (signal?.aborted) {
          reject(new DOMException('搜索已取消', 'AbortError'));
          return;
        }
        if (status !== 'complete' || !isRecord(result)) {
          reject(new Error('地址搜索失败'));
          return;
        }
        const tips = (result as AmapAutoCompleteResult).tips ?? [];
        resolve(
          tips.flatMap((tip, index) => {
            if (!tip.location || typeof tip.name !== 'string') return [];
            return [
              {
                id: tip.id || `amap-tip-${index}`,
                name: tip.name,
                district: tip.district || '地区未标注',
                coordinate: {
                  lng: tip.location.getLng(),
                  lat: tip.location.getLat(),
                  coordinateSystem: 'GCJ02' as const,
                },
              },
            ];
          }),
        );
      });
    });
  }

  convertWgs84ToGcj02(coordinate: Coordinate): Promise<Coordinate & { coordinateSystem: 'GCJ02' }> {
    if (!this.amap) return Promise.reject(new Error('地图尚未加载'));
    return new Promise((resolve, reject) => {
      this.amap?.convertFrom(lngLatTuple(coordinate), 'gps', (status, result) => {
        if (status !== 'complete' || !isRecord(result) || !Array.isArray(result.locations)) {
          reject(new Error('当前位置坐标转换失败'));
          return;
        }
        const first = result.locations[0] as Partial<AmapLngLat> | undefined;
        if (!first || typeof first.getLng !== 'function' || typeof first.getLat !== 'function') {
          reject(new Error('当前位置坐标转换结果无效'));
          return;
        }
        resolve({ lng: first.getLng(), lat: first.getLat(), coordinateSystem: 'GCJ02' });
      });
    });
  }

  setEndpointMarkers(start: SelectedPlace | null, end: SelectedPlace | null) {
    this.startMarker = this.updateEndpointMarker(this.startMarker, start, 'start');
    this.endMarker = this.updateEndpointMarker(this.endMarker, end, 'end');
  }

  setHandoffMarker(data: HandoffMarkerData | null) {
    const key = data
      ? [
          data.crossing.portalId,
          data.crossing.direction,
          data.navigationHandoff.gcj02.lng,
          data.navigationHandoff.gcj02.lat,
          data.outerEndpoint.lng,
          data.outerEndpoint.lat,
        ].join(':')
      : null;
    if (key !== null && key === this.handoffMarkerKey && this.handoffMarker) {
      this.handoffData = data;
      return;
    }
    if (this.handoffMarker) {
      this.handoffMarker.off('click', this.handoffClickHandler);
      this.handoffMarker.setMap(null);
    }
    this.handoffMarker = null;
    this.handoffMarkerKey = key;
    this.handoffData = data;
    this.handoffDescription = null;
    if (this.infoWindowKind === 'handoff') this.closeInfoWindow();
    if (!this.amap || !this.map || !data) return;

    const outbound = data.crossing.direction === 'OUTBOUND';
    this.handoffDescription = this.resolveNavigationHandoffDescription(data).catch(() => '');
    this.handoffMarker = new this.amap.Marker({
      map: this.map,
      position: lngLatTuple(data.navigationHandoff.gcj02),
      content: markerElement(outbound ? 'outbound' : 'inbound'),
      anchor: 'bottom-center',
      zIndex: 58,
      title: outbound ? '环内路线终点' : '环内路线起点',
    });
    this.handoffMarker.on('click', this.handoffClickHandler);
  }

  setRoute(geometry: OutputCoordinate[]) {
    if (!this.amap || !this.map) return;
    const path = geometry.map(lngLatTuple);
    if (!this.routePolyline) {
      this.routePolyline = new this.amap.Polyline({
        map: this.map,
        path,
        strokeColor: '#1769e0',
        strokeWeight: 7,
        strokeOpacity: 0.92,
        lineJoin: 'round',
        lineCap: 'round',
        zIndex: 40,
      });
    } else {
      this.routePolyline.setPath(path);
      this.routePolyline.setMap(this.map);
    }
  }

  clearRoute() {
    this.routePolyline?.setMap(null);
    this.routePolyline = null;
  }

  fitRoute(geometry: OutputCoordinate[]) {
    if (!this.map || geometry.length < 2) return;
    const overlays = [
      this.routePolyline,
      this.startMarker,
      this.endMarker,
      this.handoffMarker,
    ].filter((overlay): overlay is AmapPolyline | AmapMarker => overlay !== null);
    this.map.setFitView(overlays, false, [110, 80, 150, 80], 16);
  }

  setCameras(cameras: CameraView[]) {
    if (!this.amap || !this.map) return;
    this.camerasById = new Map(cameras.map((camera) => [camera.id, camera]));
    const data = cameras.map((camera) => ({ lnglat: lngLatTuple(camera), id: camera.id }));
    if (!this.cameraLayer) {
      this.cameraLayer = new this.amap.MassMarks(data, {
        zIndex: 30,
        cursor: 'pointer',
        style: {
          url: 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2216%22 height=%2216%22%3E%3Ccircle cx=%228%22 cy=%228%22 r=%226%22 fill=%22%23dc3f35%22 stroke=%22white%22 stroke-width=%222%22/%3E%3C/svg%3E',
          anchor: [8, 8],
          size: [16, 16],
        },
      });
      this.cameraLayer.on('click', this.cameraClickHandler);
    } else {
      this.cameraLayer.setData(data);
    }
    this.cameraLayer.setMap(this.map);
  }

  clearCameras() {
    if (this.cameraLayer) {
      this.cameraLayer.off('click', this.cameraClickHandler);
      this.cameraLayer.setMap(null);
    }
    this.cameraLayer = null;
    this.camerasById.clear();
    if (this.infoWindowKind === 'camera') this.closeInfoWindow();
  }

  setControlledArea(area: ControlledArea) {
    if (!this.amap || !this.map) return;
    this.clearControlledArea();
    this.controlledAreaPolygons = area.geometry.coordinates.map(
      (polygon) =>
        new this.amap!.Polygon({
          map: this.map,
          path: polygon.map((ring) => ring.map(lngLatTuple)),
          strokeColor: '#b45309',
          strokeWeight: 2,
          strokeOpacity: 0.9,
          fillColor: '#f59e0b',
          fillOpacity: 0.09,
          bubble: true,
          zIndex: 10,
        }),
    );
  }

  clearControlledArea() {
    this.controlledAreaPolygons.forEach((polygon) => polygon.setMap(null));
    this.controlledAreaPolygons = [];
  }

  setCurrentLocation(location: DisplayLocation | null) {
    if (!this.amap || !this.map || !location) {
      this.currentMarker?.setMap(null);
      this.accuracyCircle?.setMap(null);
      this.currentMarker = null;
      this.accuracyCircle = null;
      return;
    }
    const position = lngLatTuple(location.coordinate);
    if (!this.currentMarker) {
      this.currentMarker = new this.amap.Marker({
        map: this.map,
        position,
        content: markerElement('current'),
        anchor: 'center',
        zIndex: 60,
      });
      this.accuracyCircle = new this.amap.Circle({
        map: this.map,
        center: position,
        radius: location.accuracyMeters,
        strokeColor: '#11866f',
        strokeOpacity: 0.65,
        strokeWeight: 1,
        fillColor: '#35b99b',
        fillOpacity: 0.13,
        zIndex: 20,
      });
    } else {
      this.currentMarker.setPosition(position);
      this.accuracyCircle?.setCenter(position);
      this.accuracyCircle?.setRadius(location.accuracyMeters);
    }
  }

  centerOn(coordinate: Coordinate) {
    if (coordinate.coordinateSystem !== 'GCJ02') return;
    this.map?.setCenter(lngLatTuple(coordinate), true);
  }

  getViewport(): MapViewport {
    if (!this.map) {
      return { minLng: 116.2, minLat: 39.75, maxLng: 116.62, maxLat: 40.08, zoom: 11 };
    }
    const bounds = this.map.getBounds();
    return {
      minLng: bounds.getSouthWest().getLng(),
      minLat: bounds.getSouthWest().getLat(),
      maxLng: bounds.getNorthEast().getLng(),
      maxLat: bounds.getNorthEast().getLat(),
      zoom: this.map.getZoom(),
    };
  }

  subscribeViewport(listener: () => void): () => void {
    this.viewportListeners.add(listener);
    return () => this.viewportListeners.delete(listener);
  }

  private updateEndpointMarker(
    existing: AmapMarker | null,
    place: SelectedPlace | null,
    kind: 'start' | 'end',
  ): AmapMarker | null {
    if (!this.amap || !this.map || !place || place.coordinate.coordinateSystem !== 'GCJ02') {
      existing?.setMap(null);
      return null;
    }
    const position = lngLatTuple(place.coordinate);
    if (existing) {
      existing.setPosition(position);
      existing.setMap(this.map);
      return existing;
    }
    return new this.amap.Marker({
      map: this.map,
      position,
      content: markerElement(kind),
      anchor: 'bottom-center',
      zIndex: 55,
      title: place.name,
    });
  }

  private async handleMapClick(event: unknown) {
    if (Date.now() <= this.suppressMapClickUntil) return;
    const lnglat = lngLatFromEvent(event);
    if (!lnglat || !this.callbacks) return;
    const sequence = ++this.mapClickSequence;
    const coordinate = {
      lng: lnglat.getLng(),
      lat: lnglat.getLat(),
      coordinateSystem: 'GCJ02' as const,
    };
    let suggestedName = `地图选点 ${coordinate.lng.toFixed(5)}, ${coordinate.lat.toFixed(5)}`;
    if (this.amap) {
      try {
        suggestedName = await this.reverseGeocode(lngLatTuple(coordinate));
      } catch {
        // Coordinate remains usable when reverse geocoding is unavailable.
      }
    }
    if (sequence !== this.mapClickSequence || !this.callbacks) return;
    const selection = { coordinate, suggestedName };
    const content = createMapPointPopup(selection, (target) => {
      this.callbacks?.onEndpointSelect(selection, target);
      this.closeInfoWindow();
    });
    this.openInfoWindow(content, lngLatTuple(coordinate), 'map-point', [0, -8]);
  }

  private reverseGeocode(position: [number, number]): Promise<string> {
    if (!this.amap) return Promise.reject(new Error('地图尚未加载'));
    const geocoder = new this.amap.Geocoder();
    return new Promise((resolve, reject) => {
      geocoder.getAddress(position, (status, result) => {
        if (
          status !== 'complete' ||
          !isRecord(result) ||
          !isRecord(result.regeocode) ||
          typeof result.regeocode.formattedAddress !== 'string'
        ) {
          reject(new Error('逆地理编码失败'));
          return;
        }
        resolve(result.regeocode.formattedAddress);
      });
    });
  }

  private handleCameraClick(event: unknown) {
    if (!isRecord(event) || !isRecord(event.data) || typeof event.data.id !== 'string') return;
    this.suppressMapClickUntil = Date.now() + 250;
    this.mapClickSequence += 1;
    const originEvent = isRecord(event.originEvent) ? event.originEvent : null;
    if (originEvent && typeof originEvent.stopPropagation === 'function') {
      originEvent.stopPropagation.call(originEvent);
    }
    const camera = this.camerasById.get(event.data.id) ?? null;
    if (!camera || !this.amap || !this.map) return;
    const content = createCameraPopup(camera);
    this.openInfoWindow(content, lngLatTuple(camera), 'camera', [0, -10]);
  }

  private handleHandoffClick(event: unknown) {
    this.suppressMapClickUntil = Date.now() + 250;
    const originEvent = isRecord(event) && isRecord(event.originEvent) ? event.originEvent : null;
    if (originEvent && typeof originEvent.stopPropagation === 'function') {
      originEvent.stopPropagation.call(originEvent);
    }
    if (!this.handoffData || !this.handoffDescription) return;
    const content = createNavigationHandoffPopup(
      this.handoffData,
      this.handoffDescription,
      buildAmapNavigationUri(this.handoffData),
      () => {
        this.callbacks?.onHandoffSegmentSelect();
        this.closeInfoWindow();
      },
    );
    this.openInfoWindow(
      content,
      lngLatTuple(this.handoffData.navigationHandoff.gcj02),
      'handoff',
      [0, -12],
    );
  }

  private resolveNavigationHandoffDescription(data: HandoffMarkerData): Promise<string> {
    if (data.navigationHandoff.type === 'HIGHWAY') {
      return Promise.resolve(data.navigationHandoff.roadName || '受控区外高速交接点');
    }
    if (!this.amap) return Promise.reject(new Error('地图尚未加载'));
    const poiRadius = data.externalHandoff.poiSearchRadiusMeters;
    const geocoder = new this.amap.Geocoder({
      extensions: 'all',
      radius: Math.max(1, poiRadius),
    });
    return new Promise((resolve, reject) => {
      geocoder.getAddress(lngLatTuple(data.navigationHandoff.gcj02), (status, result) => {
        if (status !== 'complete' || !isRecord(result) || !isRecord(result.regeocode)) {
          reject(new Error('交接点附近地标解析失败'));
          return;
        }
        const regeocode = result.regeocode;
        let placeName = '';
        if (poiRadius > 0 && Array.isArray(regeocode.pois)) {
          const poi = selectHandoffPoi(regeocode.pois, poiRadius);
          if (poi) placeName = describeHandoffPoi(regeocode, poi);
        }
        if (!placeName && typeof regeocode.formattedAddress === 'string') {
          placeName = regeocode.formattedAddress;
        }
        if (!placeName) {
          reject(new Error('交接点附近没有可搜索描述'));
          return;
        }
        resolve(`${placeName}附近`);
      });
    });
  }

  private openInfoWindow(
    content: HTMLElement,
    position: [number, number],
    kind: 'camera' | 'map-point' | 'handoff',
    offset: [number, number],
  ) {
    if (!this.amap || !this.map) return;
    this.infoWindow?.close();
    this.infoWindow = new this.amap.InfoWindow({ content, offset });
    this.infoWindowKind = kind;
    this.container?.classList.add('map-container--popup-open');
    this.infoWindow.open(this.map, position);
  }

  private closeInfoWindow() {
    this.infoWindow?.close();
    this.infoWindow = null;
    this.infoWindowKind = null;
    this.container?.classList.remove('map-container--popup-open');
  }
}
