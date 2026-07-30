import { environment } from '@/config/environment';
import type { ApiClient } from '@/services/apiClient';

let clientPromise: Promise<ApiClient> | undefined;

export function getApiClient(): Promise<ApiClient> {
  clientPromise ??= environment.useMockApi
    ? import('@/mocks/mockApiClient').then(({ MockApiClient }) => new MockApiClient())
    : import('@/services/httpApiClient').then(
        ({ HttpApiClient }) => new HttpApiClient(environment.apiBaseUrl),
      );
  return clientPromise;
}
