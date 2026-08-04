import type { ApiErrorCode } from '@/types/api';

export type RouteFailureState =
  'no-route' | 'endpoint-restricted' | 'outside-bounds' | 'unavailable' | 'protocol-error';

export interface RouteErrorPresentation {
  state: RouteFailureState;
  title: string;
  message: string;
  retryable: boolean;
}

const ERROR_PRESENTATIONS: Record<ApiErrorCode, RouteErrorPresentation> = {
  INVALID_REQUEST: {
    state: 'protocol-error',
    title: '请求无法处理',
    message: '请重新选择有效的起点和终点。',
    retryable: false,
  },
  INVALID_COORDINATE: {
    state: 'protocol-error',
    title: '坐标无效',
    message: '请清空后重新选择该地点。',
    retryable: false,
  },
  UNSUPPORTED_COORDINATE_SYSTEM: {
    state: 'protocol-error',
    title: '坐标系不受支持',
    message: '请重新选择地点；若仍失败，请检查服务版本。',
    retryable: false,
  },
  OUTSIDE_ROUTING_BOUNDS: {
    state: 'outside-bounds',
    title: '超出可规划范围',
    message: '起点或终点不在当前路网覆盖范围，请选择京津冀范围内地点。',
    retryable: false,
  },
  START_IN_RESTRICTED_AREA: {
    state: 'endpoint-restricted',
    title: '起点位于避让范围',
    message: '请将起点移到限制点位安全范围外。',
    retryable: false,
  },
  END_IN_RESTRICTED_AREA: {
    state: 'endpoint-restricted',
    title: '终点位于避让范围',
    message: '请将终点移到限制点位安全范围外。',
    retryable: false,
  },
  NO_COMPLIANT_ROUTE: {
    state: 'no-route',
    title: '没有合规路线',
    message: '当前限制点位下无法安全到达，请更换起点或终点。',
    retryable: false,
  },
  ROUTE_SEARCH_TIMEOUT: {
    state: 'unavailable',
    title: '路线搜索超时',
    message: '本次搜索未在时限内完成，请稍后重试。',
    retryable: true,
  },
  ROUTE_SEARCH_RESOURCE_LIMIT: {
    state: 'unavailable',
    title: '路线搜索繁忙',
    message: '本次搜索达到资源上限，请稍后重试。',
    retryable: true,
  },
  SIXTH_RING_TOPOLOGY_NOT_READY: {
    state: 'unavailable',
    title: '六环拓扑未就绪',
    message: '六环边界和通行口仍在加载，请稍后重试。',
    retryable: true,
  },
  SIXTH_RING_BOUNDARY_AMBIGUOUS: {
    state: 'outside-bounds',
    title: '点位位于六环边界带',
    message: '请将起点或终点调整到六环内侧或外侧后重试。',
    retryable: false,
  },
  REFERENCE_ROUTE_FAILED: {
    state: 'unavailable',
    title: '参考路线生成失败',
    message: '环内路线可达，但完整参考路线暂时无法生成，请稍后重试。',
    retryable: true,
  },
  ROUTE_CONFLICT_DETECTED: {
    state: 'protocol-error',
    title: '路线未通过安全校验',
    message: '服务拒绝了存在点位冲突的路线，页面不会显示该路线。',
    retryable: true,
  },
  ROUTING_NOT_READY: {
    state: 'unavailable',
    title: '路由服务未就绪',
    message: '路网仍在加载，请稍后重试。',
    retryable: true,
  },
  CAMERA_SNAPSHOT_NOT_READY: {
    state: 'unavailable',
    title: '点位快照未就绪',
    message: '当前没有可用的限制点位快照，请稍后重试。',
    retryable: true,
  },
  REFRESH_ALREADY_RUNNING: {
    state: 'unavailable',
    title: '点位正在更新',
    message: '请等待当前更新完成后重试。',
    retryable: true,
  },
  CAMERA_UPDATE_ALREADY_RUNNING: {
    state: 'unavailable',
    title: '点位数据正在更新',
    message: '请等待当前数据更新完成后重试。',
    retryable: true,
  },
  CAMERA_UPDATE_FAILED: {
    state: 'unavailable',
    title: '点位数据更新失败',
    message: '当前更新未生效，请稍后重试或检查服务日志。',
    retryable: true,
  },
  INTERNAL_ERROR: {
    state: 'unavailable',
    title: '服务暂时不可用',
    message: '请检查网络连接并稍后重试。',
    retryable: true,
  },
};

export function presentationForApiError(code: ApiErrorCode): RouteErrorPresentation {
  return ERROR_PRESENTATIONS[code];
}

export const NETWORK_ERROR_PRESENTATION: RouteErrorPresentation = {
  state: 'unavailable',
  title: '无法连接路线服务',
  message: '请检查网络连接和 API 地址后重试。',
  retryable: true,
};

export const PROTOCOL_ERROR_PRESENTATION: RouteErrorPresentation = {
  state: 'protocol-error',
  title: '路线响应未通过校验',
  message: '页面已拒绝显示不符合安全契约的路线。',
  retryable: true,
};
