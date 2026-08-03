import { loadAmap } from './amapLoader';
import { createCameraPopup, createMapPointPopup } from './mapPopupContent';
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
} from '@/types/amap';
import type { CameraView, OutputCoordinate } from '@/types/api';
import type { Coordinate, DisplayLocation, SelectedPlace } from '@/types/coordinate';
import type { MapAdapter, MapAdapterCallbacks, MapViewport, PlaceSuggestion } from '@/types/map';
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

function markerElement(kind: 'start' | 'end' | 'current'): HTMLDivElement {
  const marker = document.createElement('div');
  marker.className = `map-marker map-marker--${kind}`;
  marker.setAttribute('aria-hidden', 'true');
  if (kind !== 'current') marker.textContent = kind === 'start' ? '起' : '终';
  return marker;
}

export class AmapMapAdapter implements MapAdapter {
  private amap: AmapNamespace | null = null;
  private map: AmapMap | null = null;
  private callbacks: MapAdapterCallbacks | null = null;
  private startMarker: AmapMarker | null = null;
  private endMarker: AmapMarker | null = null;
  private routePolyline: AmapPolyline | null = null;
  private cameraLayer: AmapMassMarks | null = null;
  private currentMarker: AmapMarker | null = null;
  private accuracyCircle: AmapCircle | null = null;
  private infoWindow: AmapInfoWindow | null = null;
  private infoWindowKind: 'camera' | 'map-point' | null = null;
  private camerasById = new Map<string, CameraView>();
  private viewportListeners = new Set<() => void>();
  private mapClickSequence = 0;
  private suppressMapClickUntil = 0;
  private readonly mapClickHandler = (event?: unknown) => void this.handleMapClick(event);
  private readonly viewportHandler = () => this.viewportListeners.forEach((listener) => listener());
  private readonly cameraClickHandler = (event: unknown) => this.handleCameraClick(event);

  constructor(
    private readonly key: string,
    private readonly securityCode: string,
  ) {}

  async initialize(container: HTMLElement, callbacks: MapAdapterCallbacks): Promise<void> {
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
    this.setEndpointMarkers(null, null);
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
    const overlays = [this.routePolyline, this.startMarker, this.endMarker].filter(
      (overlay): overlay is AmapPolyline | AmapMarker => overlay !== null,
    );
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

  private openInfoWindow(
    content: HTMLElement,
    position: [number, number],
    kind: 'camera' | 'map-point',
    offset: [number, number],
  ) {
    if (!this.amap || !this.map) return;
    this.infoWindow?.close();
    this.infoWindow = new this.amap.InfoWindow({ content, offset });
    this.infoWindowKind = kind;
    this.infoWindow.open(this.map, position);
  }

  private closeInfoWindow() {
    this.infoWindow?.close();
    this.infoWindow = null;
    this.infoWindowKind = null;
  }
}
