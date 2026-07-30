import { afterEach, describe, expect, it, vi } from 'vitest';

import { HttpApiClient } from '@/services/httpApiClient';

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

describe('HTTP API client', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('parses the frozen NOT_READY body returned with HTTP 503', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response(503, {
          status: 'NOT_READY',
          graphLoaded: true,
          cameraSnapshotLoaded: false,
          blockedEdgesLoaded: false,
          reason: '摄像头快照正在加载',
        }),
      ),
    );

    const readiness = await new HttpApiClient('').readiness();

    expect(readiness).toEqual({
      status: 'NOT_READY',
      graphLoaded: true,
      cameraSnapshotLoaded: false,
      blockedEdgesLoaded: false,
      reason: '摄像头快照正在加载',
    });
    expect(fetch).toHaveBeenCalledWith('/api/v1/readiness', expect.any(Object));
  });
});
