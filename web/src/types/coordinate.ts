export type CoordinateSystem = 'WGS84' | 'GCJ02';
export type CoordinateSource = 'AMAP_SEARCH' | 'MAP_PICK' | 'CURRENT_LOCATION';

export interface Coordinate {
  lng: number;
  lat: number;
  coordinateSystem: CoordinateSystem;
}

export interface InputCoordinate extends Coordinate {
  source: CoordinateSource;
  accuracyMeters?: number;
  timestamp?: string;
}

export interface SelectedPlace {
  name: string;
  coordinate: Coordinate;
  source: CoordinateSource;
  accuracyMeters?: number;
  timestamp?: string;
}

export interface BrowserLocation {
  coordinate: Coordinate & { coordinateSystem: 'WGS84' };
  accuracyMeters: number;
  heading: number | null;
  speed: number | null;
  timestamp: string;
}

export interface DisplayLocation {
  coordinate: Coordinate & { coordinateSystem: 'GCJ02' };
  accuracyMeters: number;
  timestamp: string;
}
