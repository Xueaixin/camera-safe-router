export interface AppEnvironment {
  amapKey: string;
  amapSecurityCode: string;
  apiBaseUrl: string;
  useMockApi: boolean;
}

function normalizedBaseUrl(value: string): string {
  return value.replace(/\/+$/, '');
}

export const environment: AppEnvironment = Object.freeze({
  amapKey: import.meta.env.VITE_AMAP_KEY?.trim() ?? '',
  amapSecurityCode: import.meta.env.VITE_AMAP_SECURITY_CODE?.trim() ?? '',
  apiBaseUrl: normalizedBaseUrl(import.meta.env.VITE_API_BASE_URL || ''),
  useMockApi: import.meta.env.VITE_USE_MOCK_API === 'true',
});
