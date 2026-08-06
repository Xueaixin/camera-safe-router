import { MOCK_SEARCH_PLACES } from '@/mocks/fixtures';
import {
  createCameraPopup,
  createMapPointPopup,
  createNavigationHandoffPopup,
} from './mapPopupContent';
import { buildAmapNavigationUri } from './amapUri';
import type { CameraView, OutputCoordinate } from '@/types/api';
import type { Coordinate, DisplayLocation, SelectedPlace } from '@/types/coordinate';
import type {
  HandoffMarkerData,
  MapAdapter,
  MapAdapterCallbacks,
  MapViewport,
  PlaceSuggestion,
} from '@/types/map';

const MOCK_VIEWPORT: MapViewport = {
  minLng: 116.25,
  minLat: 39.82,
  maxLng: 116.55,
  maxLat: 40.04,
  zoom: 11,
};

export class MockMapAdapter implements MapAdapter {
  private container: HTMLElement | null = null;
  private canvas: HTMLCanvasElement | null = null;
  private callbacks: MapAdapterCallbacks | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private route: OutputCoordinate[] = [];
  private cameras: CameraView[] = [];
  private start: SelectedPlace | null = null;
  private end: SelectedPlace | null = null;
  private handoff: HandoffMarkerData | null = null;
  private location: DisplayLocation | null = null;
  private pendingSelection: { lng: number; lat: number } | null = null;
  private popup: HTMLElement | null = null;
  private popupKind: 'camera' | 'map-point' | 'handoff' | null = null;
  private viewportListeners = new Set<() => void>();
  private readonly clickHandler = (event: MouseEvent) => this.handleClick(event);

  async initialize(container: HTMLElement, callbacks: MapAdapterCallbacks): Promise<void> {
    this.container = container;
    this.callbacks = callbacks;
    container.classList.add('mock-map');
    container.dataset.mapMode = 'mock';
    const canvas = document.createElement('canvas');
    canvas.className = 'mock-map__canvas';
    canvas.setAttribute('aria-label', '模拟地图画布');
    const label = document.createElement('div');
    label.className = 'mock-map__label';
    label.textContent = '模拟地图';
    container.replaceChildren(canvas, label);
    this.canvas = canvas;
    canvas.addEventListener('click', this.clickHandler);
    this.resizeObserver = new ResizeObserver(() => this.draw());
    this.resizeObserver.observe(container);
    this.draw();
  }

  destroy() {
    this.canvas?.removeEventListener('click', this.clickHandler);
    this.resizeObserver?.disconnect();
    this.closePopup();
    this.container?.replaceChildren();
    this.container = null;
    this.canvas = null;
    this.callbacks = null;
    this.viewportListeners.clear();
  }

  async searchPlaces(keyword: string, signal?: AbortSignal): Promise<PlaceSuggestion[]> {
    await new Promise((resolve) => window.setTimeout(resolve, 120));
    if (signal?.aborted) throw new DOMException('搜索已取消', 'AbortError');
    const normalized = keyword.trim().toLowerCase();
    return MOCK_SEARCH_PLACES.filter(
      (place) =>
        place.name.toLowerCase().includes(normalized) || place.district.includes(keyword.trim()),
    ).map((place) => ({
      id: place.id,
      name: place.name,
      district: place.district,
      coordinate: { lng: place.lng, lat: place.lat, coordinateSystem: 'GCJ02' },
    }));
  }

  async convertWgs84ToGcj02(
    coordinate: Coordinate,
  ): Promise<Coordinate & { coordinateSystem: 'GCJ02' }> {
    return {
      lng: coordinate.lng + 0.0065,
      lat: coordinate.lat + 0.0015,
      coordinateSystem: 'GCJ02',
    };
  }

  setEndpointMarkers(start: SelectedPlace | null, end: SelectedPlace | null) {
    this.start = start;
    this.end = end;
    this.draw();
  }

  setHandoffMarker(data: HandoffMarkerData | null) {
    this.handoff = data;
    if (this.container) {
      this.container.dataset.handoffMarker = data?.crossing.direction.toLowerCase() ?? 'none';
    }
    if (!data && this.popupKind === 'handoff') this.closePopup();
    this.draw();
  }

  setRoute(geometry: OutputCoordinate[]) {
    this.route = geometry;
    if (this.container) this.container.dataset.routePoints = String(geometry.length);
    this.draw();
  }

  clearRoute() {
    this.route = [];
    if (this.container) this.container.dataset.routePoints = '0';
    this.draw();
  }

  fitRoute() {
    if (this.container) this.container.dataset.fittedRoute = 'true';
  }

  setCameras(cameras: CameraView[]) {
    this.cameras = cameras;
    if (this.container) this.container.dataset.cameraCount = String(cameras.length);
    this.draw();
  }

  clearCameras() {
    this.cameras = [];
    if (this.container) this.container.dataset.cameraCount = '0';
    if (this.popupKind === 'camera') this.closePopup();
    this.draw();
  }

  setCurrentLocation(location: DisplayLocation | null) {
    this.location = location;
    if (this.container) this.container.dataset.hasCurrentLocation = String(Boolean(location));
    this.draw();
  }

  centerOn(coordinate: Coordinate) {
    if (this.container) {
      this.container.dataset.centered = 'current';
      this.container.dataset.center = `${coordinate.lng},${coordinate.lat}`;
    }
  }

  getViewport(): MapViewport {
    return MOCK_VIEWPORT;
  }

  subscribeViewport(listener: () => void): () => void {
    this.viewportListeners.add(listener);
    return () => this.viewportListeners.delete(listener);
  }

  private handleClick(event: MouseEvent) {
    if (!this.canvas || !this.callbacks) return;
    const bounds = this.canvas.getBoundingClientRect();
    const x = (event.clientX - bounds.left) / Math.max(bounds.width, 1);
    const y = (event.clientY - bounds.top) / Math.max(bounds.height, 1);
    if (this.handoff) {
      const [handoffX, handoffY] = this.project(
        this.handoff.navigationHandoff.gcj02,
        bounds.width,
        bounds.height,
      );
      if (Math.hypot(handoffX - x * bounds.width, handoffY - y * bounds.height) <= 18) {
        const description = Promise.resolve('北京市昌平区小汤山镇阿苏卫收费站附近');
        const content = createNavigationHandoffPopup(
          this.handoff,
          description,
          buildAmapNavigationUri(this.handoff),
          () => {
            this.callbacks?.onHandoffSegmentSelect();
            this.closePopup();
          },
        );
        this.openPopup(content, handoffX, handoffY, 'handoff');
        return;
      }
    }
    const nearestCamera = this.cameras.find((camera) => {
      const [cameraX, cameraY] = this.project(camera, bounds.width, bounds.height);
      return Math.hypot(cameraX - x * bounds.width, cameraY - y * bounds.height) <= 16;
    });
    if (nearestCamera) {
      const [cameraX, cameraY] = this.project(nearestCamera, bounds.width, bounds.height);
      this.openPopup(createCameraPopup(nearestCamera), cameraX, cameraY, 'camera');
      return;
    }
    const lng = MOCK_VIEWPORT.minLng + x * (MOCK_VIEWPORT.maxLng - MOCK_VIEWPORT.minLng);
    const lat = MOCK_VIEWPORT.maxLat - y * (MOCK_VIEWPORT.maxLat - MOCK_VIEWPORT.minLat);
    const selection = {
      coordinate: { lng, lat, coordinateSystem: 'GCJ02' },
      suggestedName: `地图选点 ${lng.toFixed(5)}, ${lat.toFixed(5)}`,
    } as const;
    const content = createMapPointPopup(selection, (target) => {
      this.callbacks?.onEndpointSelect(selection, target);
      this.pendingSelection = null;
      this.closePopup();
      this.draw();
    });
    this.openPopup(content, x * bounds.width, y * bounds.height, 'map-point');
    this.pendingSelection = selection.coordinate;
    this.draw();
  }

  private openPopup(
    popup: HTMLElement,
    x: number,
    y: number,
    kind: 'camera' | 'map-point' | 'handoff',
  ) {
    if (!this.container) return;
    const replacedSelection = this.popupKind === 'map-point';
    this.closePopup();
    if (replacedSelection) {
      this.pendingSelection = null;
      this.draw();
    }
    const horizontalInset = Math.min(150, Math.max(16, this.container.clientWidth / 2));
    popup.classList.add('mock-map-popup');
    popup.style.left = `${Math.min(
      Math.max(x, horizontalInset),
      Math.max(horizontalInset, this.container.clientWidth - horizontalInset),
    )}px`;
    popup.style.top = `${y}px`;
    if (y < 170) popup.classList.add('mock-map-popup--below');
    this.container.append(popup);
    this.container.classList.add('map-container--popup-open');
    this.popup = popup;
    this.popupKind = kind;
  }

  private closePopup() {
    this.popup?.remove();
    this.container?.classList.remove('map-container--popup-open');
    this.popup = null;
    this.popupKind = null;
  }

  private draw() {
    if (!this.canvas || !this.container) return;
    const width = Math.max(1, Math.floor(this.container.clientWidth));
    const height = Math.max(1, Math.floor(this.container.clientHeight));
    const ratio = window.devicePixelRatio || 1;
    this.canvas.width = width * ratio;
    this.canvas.height = height * ratio;
    this.canvas.style.width = `${width}px`;
    this.canvas.style.height = `${height}px`;
    const context = this.canvas.getContext('2d');
    if (!context) return;
    context.scale(ratio, ratio);
    context.fillStyle = '#e9eef1';
    context.fillRect(0, 0, width, height);
    context.strokeStyle = '#ffffff';
    context.lineWidth = 5;
    for (let index = 1; index < 8; index += 1) {
      const x = (width / 8) * index;
      const y = (height / 8) * index;
      context.beginPath();
      context.moveTo(x, 0);
      context.lineTo(x * 0.82 + width * 0.1, height);
      context.stroke();
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(width, y * 0.86 + height * 0.06);
      context.stroke();
    }
    this.drawPath(context, this.route, width, height);
    this.cameras.forEach((camera) => this.drawPoint(context, camera, width, height, '#dc3f35', 5));
    if (this.pendingSelection) {
      this.drawPoint(context, this.pendingSelection, width, height, '#1769e0', 7);
    }
    if (this.handoff) {
      const color = this.handoff.crossing.direction === 'OUTBOUND' ? '#b45309' : '#087f6b';
      this.drawPoint(context, this.handoff.navigationHandoff.gcj02, width, height, color, 9);
    }
    if (this.start?.coordinate.coordinateSystem === 'GCJ02') {
      this.drawPoint(context, this.start.coordinate, width, height, '#15803d', 8);
    }
    if (this.end?.coordinate.coordinateSystem === 'GCJ02') {
      this.drawPoint(context, this.end.coordinate, width, height, '#1d2939', 8);
    }
    if (this.location) {
      this.drawPoint(context, this.location.coordinate, width, height, '#087f6b', 8);
    }
  }

  private drawPath(
    context: CanvasRenderingContext2D,
    geometry: OutputCoordinate[],
    width: number,
    height: number,
  ) {
    if (geometry.length < 2) return;
    context.beginPath();
    geometry.forEach((point, index) => {
      const [x, y] = this.project(point, width, height);
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.strokeStyle = '#1769e0';
    context.lineWidth = 7;
    context.lineCap = 'round';
    context.lineJoin = 'round';
    context.stroke();
  }

  private drawPoint(
    context: CanvasRenderingContext2D,
    point: { lng: number; lat: number },
    width: number,
    height: number,
    color: string,
    radius: number,
  ) {
    const [x, y] = this.project(point, width, height);
    context.beginPath();
    context.arc(x, y, radius, 0, Math.PI * 2);
    context.fillStyle = color;
    context.fill();
    context.strokeStyle = '#ffffff';
    context.lineWidth = 2;
    context.stroke();
  }

  private project(
    point: { lng: number; lat: number },
    width: number,
    height: number,
  ): [number, number] {
    return [
      ((point.lng - MOCK_VIEWPORT.minLng) / (MOCK_VIEWPORT.maxLng - MOCK_VIEWPORT.minLng)) * width,
      ((MOCK_VIEWPORT.maxLat - point.lat) / (MOCK_VIEWPORT.maxLat - MOCK_VIEWPORT.minLat)) * height,
    ];
  }
}
