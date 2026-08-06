export interface AmapLngLat {
  getLng(): number;
  getLat(): number;
}

export interface AmapBounds {
  getSouthWest(): AmapLngLat;
  getNorthEast(): AmapLngLat;
}

export interface AmapMap {
  on(event: string, handler: (event?: unknown) => void): void;
  off(event: string, handler: (event?: unknown) => void): void;
  destroy(): void;
  setCenter(position: [number, number], immediately?: boolean): void;
  setFitView(
    overlays?: unknown[],
    immediately?: boolean,
    avoid?: [number, number, number, number],
    maxZoom?: number,
  ): void;
  getBounds(): AmapBounds;
  getZoom(): number;
}

export interface AmapOverlay {
  setMap(map: AmapMap | null): void;
}

export interface AmapMarker extends AmapOverlay {
  on(event: string, handler: (event: unknown) => void): void;
  off(event: string, handler: (event: unknown) => void): void;
  setPosition(position: [number, number]): void;
}

export interface AmapPolyline extends AmapOverlay {
  setPath(path: [number, number][]): void;
}

export interface AmapCircle extends AmapOverlay {
  setCenter(position: [number, number]): void;
  setRadius(radius: number): void;
}

export interface AmapMassMarks extends AmapOverlay {
  setData(data: AmapMassMarkData[]): void;
  on(event: string, handler: (event: unknown) => void): void;
  off(event: string, handler: (event: unknown) => void): void;
}

export interface AmapInfoWindow {
  open(map: AmapMap, position: [number, number]): void;
  close(): void;
}

export interface AmapMassMarkData {
  lnglat: [number, number];
  id: string;
}

export interface AmapTip {
  id?: string;
  name?: string;
  district?: string;
  location?: AmapLngLat;
}

export interface AmapAutoCompleteResult {
  tips?: AmapTip[];
}

export interface AmapAutoComplete {
  search(keyword: string, callback: (status: string, result: unknown) => void): void;
}

export interface AmapGeocoder {
  getAddress(position: [number, number], callback: (status: string, result: unknown) => void): void;
}

export interface AmapNamespace {
  Map: new (container: HTMLElement, options: Record<string, unknown>) => AmapMap;
  Marker: new (options: Record<string, unknown>) => AmapMarker;
  Polyline: new (options: Record<string, unknown>) => AmapPolyline;
  Circle: new (options: Record<string, unknown>) => AmapCircle;
  MassMarks: new (data: AmapMassMarkData[], options: Record<string, unknown>) => AmapMassMarks;
  InfoWindow: new (options: Record<string, unknown>) => AmapInfoWindow;
  AutoComplete: new (options?: Record<string, unknown>) => AmapAutoComplete;
  Geocoder: new (options?: Record<string, unknown>) => AmapGeocoder;
  convertFrom(
    position: [number, number],
    source: 'gps',
    callback: (status: string, result: unknown) => void,
  ): void;
}

declare global {
  interface Window {
    AMap?: AmapNamespace;
    _AMapSecurityConfig?: {
      securityJsCode: string;
    };
  }
}
