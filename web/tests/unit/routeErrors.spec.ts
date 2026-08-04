import { describe, expect, it } from 'vitest';

import { presentationForApiError } from '@/utils/routeErrors';

describe('route error mapping', () => {
  it.each([
    ['NO_COMPLIANT_ROUTE', 'no-route'],
    ['START_IN_RESTRICTED_AREA', 'endpoint-restricted'],
    ['END_IN_RESTRICTED_AREA', 'endpoint-restricted'],
    ['OUTSIDE_ROUTING_BOUNDS', 'outside-bounds'],
    ['SIXTH_RING_BOUNDARY_AMBIGUOUS', 'outside-bounds'],
    ['ROUTE_SEARCH_TIMEOUT', 'unavailable'],
    ['ROUTE_SEARCH_RESOURCE_LIMIT', 'unavailable'],
    ['SIXTH_RING_TOPOLOGY_NOT_READY', 'unavailable'],
    ['REFERENCE_ROUTE_FAILED', 'unavailable'],
    ['ROUTING_NOT_READY', 'unavailable'],
    ['ROUTE_CONFLICT_DETECTED', 'protocol-error'],
  ] as const)('maps %s to %s without branching on backend message', (code, state) => {
    expect(presentationForApiError(code).state).toBe(state);
  });
});
