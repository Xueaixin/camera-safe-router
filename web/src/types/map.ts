import type {
  BoundaryCrossing,
  CameraView,
  ControlledArea,
  ExternalHandoff,
  NavigationHandoff,
  OutputCoordinate,
} from './api';
import type { Coordinate, DisplayLocation, SelectedPlace } from './coordinate';

export interface PlaceSuggestion {
  id: string;
  name: string;
  district: string;
  coordinate: Coordinate & { coordinateSystem: 'GCJ02' };
}

export interface MapSelection {
  coordinate: Coordinate & { coordinateSystem: 'GCJ02' };
  suggestedName: string;
}

export type MapEndpointTarget = 'start' | 'end';

export interface MapViewport {
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
  zoom: number;
}

export interface MapAdapterCallbacks {
  onEndpointSelect: (selection: MapSelection, target: MapEndpointTarget) => void;
  onHandoffSegmentSelect: () => void;
}

export interface HandoffMarkerData {
  crossing: BoundaryCrossing;
  navigationHandoff: NavigationHandoff;
  externalHandoff: ExternalHandoff;
  outerEndpoint: OutputCoordinate;
}

export interface MapAdapter {
  initialize(container: HTMLElement, callbacks: MapAdapterCallbacks): Promise<void>;
  destroy(): void;
  searchPlaces(keyword: string, signal?: AbortSignal): Promise<PlaceSuggestion[]>;
  convertWgs84ToGcj02(coordinate: Coordinate): Promise<Coordinate & { coordinateSystem: 'GCJ02' }>;
  setEndpointMarkers(start: SelectedPlace | null, end: SelectedPlace | null): void;
  setHandoffMarker(data: HandoffMarkerData | null): void;
  setRoute(geometry: OutputCoordinate[]): void;
  clearRoute(): void;
  fitRoute(geometry: OutputCoordinate[]): void;
  setCameras(cameras: CameraView[]): void;
  clearCameras(): void;
  setControlledArea(area: ControlledArea): void;
  clearControlledArea(): void;
  setCurrentLocation(location: DisplayLocation | null): void;
  centerOn(coordinate: Coordinate): void;
  getViewport(): MapViewport;
  subscribeViewport(listener: () => void): () => void;
}
