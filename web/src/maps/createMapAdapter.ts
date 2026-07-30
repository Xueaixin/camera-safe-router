import { environment } from '@/config/environment';
import type { MapAdapter } from '@/types/map';

export async function createMapAdapter(): Promise<MapAdapter> {
  if (environment.useMockApi) {
    const { MockMapAdapter } = await import('./mockMapAdapter');
    return new MockMapAdapter();
  }
  const { AmapMapAdapter } = await import('./amapMapAdapter');
  return new AmapMapAdapter(environment.amapKey, environment.amapSecurityCode);
}
