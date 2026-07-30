import type {
  ApiErrorCode,
  CameraPage,
  CameraSnapshotStatus,
  RouteRequest,
  RouteResponse,
} from '@/types/api';

export const MOCK_SEARCH_PLACES = [
  { id: 'place-tiananmen', name: '天安门广场', district: '东城区', lng: 116.397, lat: 39.908 },
  { id: 'place-guomao', name: '国贸中心', district: '朝阳区', lng: 116.46, lat: 39.908 },
  { id: 'place-wangjing', name: '望京SOHO', district: '朝阳区', lng: 116.47, lat: 39.992 },
  { id: 'place-zhongguancun', name: '中关村', district: '海淀区', lng: 116.316, lat: 39.983 },
  { id: 'place-beijing-south', name: '北京南站', district: '丰台区', lng: 116.379, lat: 39.865 },
] as const;

export const MOCK_CAMERA_PAGE: CameraPage = {
  coordinateSystem: 'GCJ02',
  snapshotVersion: 'fixture-camera-v1',
  items: [
    {
      id: 'camera-fixture-001',
      lng: 116.42,
      lat: 39.93,
      address: '示例道路与示例路交叉口',
      cameraType: '拍进京证',
      directionText: '东向西',
    },
    {
      id: 'camera-fixture-002',
      lng: 116.438,
      lat: 39.945,
      address: '建国路示例点位',
      cameraType: '综合监控',
      directionText: '西向东',
    },
    {
      id: 'camera-fixture-003',
      lng: 116.452,
      lat: 39.971,
      address: '东四环示例点位',
      cameraType: '拍进京证',
      directionText: '进京',
    },
    {
      id: 'camera-fixture-004',
      lng: 116.399,
      lat: 39.958,
      address: '北二环示例点位',
      cameraType: '综合监控',
      directionText: null,
    },
    {
      id: 'camera-fixture-005',
      lng: 116.365,
      lat: 39.929,
      address: '西城区示例点位',
      cameraType: '拍进京证',
      directionText: '南向北',
    },
  ],
};

export const MOCK_SNAPSHOT: CameraSnapshotStatus = {
  status: 'READY',
  snapshotVersion: 'fixture-camera-v1',
  blockedEdgeVersion: 'fixture-blocked-v1',
  cameraCount: 6797,
  safetyRadiusMeters: 30,
  loadedAt: '2026-07-30T00:00:00Z',
};

export function buildMockRoute(
  request: RouteRequest,
  routeId = 'route-fixture-001',
): RouteResponse {
  const displayCoordinate = (coordinate: RouteRequest['start']) =>
    coordinate.coordinateSystem === 'WGS84'
      ? { lng: coordinate.lng + 0.0065, lat: coordinate.lat + 0.0015 }
      : { lng: coordinate.lng, lat: coordinate.lat };
  const start = displayCoordinate(request.start);
  const end = displayCoordinate(request.end);
  return {
    routeId,
    coordinateSystem: 'GCJ02',
    distanceMeters: 12640.5,
    durationSeconds: 1680,
    cameraConflictCount: 0,
    cameraSnapshotVersion: 'fixture-camera-v1',
    blockedEdgeVersion: 'fixture-blocked-v1',
    geometry: [
      { lng: start.lng, lat: start.lat },
      {
        lng: start.lng + (end.lng - start.lng) * 0.32,
        lat: start.lat + (end.lat - start.lat) * 0.25,
      },
      {
        lng: start.lng + (end.lng - start.lng) * 0.67,
        lat: start.lat + (end.lat - start.lat) * 0.72,
      },
      { lng: end.lng, lat: end.lat },
    ],
    steps: [
      {
        instruction: '向东行驶',
        roadName: '示例道路',
        distanceMeters: 3200,
        durationSeconds: 420,
        startIndex: 0,
        endIndex: 1,
      },
    ],
  };
}

export function mockApiError(code: ApiErrorCode) {
  const messages: Record<ApiErrorCode, string> = {
    INVALID_REQUEST: '请求格式不正确',
    INVALID_COORDINATE: '坐标不正确',
    UNSUPPORTED_COORDINATE_SYSTEM: '不支持该坐标系',
    OUTSIDE_ROUTING_BOUNDS: '起点或终点不在当前路网支持范围',
    START_IN_RESTRICTED_AREA: '起点位于限制范围内',
    END_IN_RESTRICTED_AREA: '终点位于限制范围内',
    NO_COMPLIANT_ROUTE: '未找到能够避开当前限制点位的路线',
    ROUTE_CONFLICT_DETECTED: '路线安全校验发现冲突',
    ROUTING_NOT_READY: '路由服务尚未就绪',
    CAMERA_SNAPSHOT_NOT_READY: '摄像头快照尚未就绪',
    REFRESH_ALREADY_RUNNING: '快照刷新正在运行',
    INTERNAL_ERROR: '服务暂时不可用',
  };
  return {
    code,
    message: messages[code],
    requestId: 'request-fixture-001',
    timestamp: '2026-07-30T00:00:00Z',
  };
}
