import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadAmap } from '@/maps/amapLoader';
import { AmapMapAdapter } from '@/maps/amapMapAdapter';
import type { AmapLngLat, AmapNamespace } from '@/types/amap';

vi.mock('@/maps/amapLoader', () => ({ loadAmap: vi.fn() }));

function lngLat(lng: number, lat: number): AmapLngLat {
  return { getLng: () => lng, getLat: () => lat };
}

describe('AmapMapAdapter place lookup', () => {
  const autocompleteOptions = vi.fn();
  const geocoderOptions = vi.fn();
  const onEndpointSelect = vi.fn();
  const onHandoffSegmentSelect = vi.fn();
  let mapClickHandler: ((event?: unknown) => void) | null;
  let cameraClickHandler: ((event: unknown) => void) | null;
  let handoffClickHandler: ((event: unknown) => void) | null;
  let infoWindowContent: HTMLElement | null;
  let infoWindowPosition: [number, number] | null;
  let geocoderPositions: [number, number][];
  let poiDistance: string;
  let includeCloserHotel: boolean;

  beforeEach(() => {
    vi.clearAllMocks();
    mapClickHandler = null;
    cameraClickHandler = null;
    handoffClickHandler = null;
    infoWindowContent = null;
    infoWindowPosition = null;
    geocoderPositions = [];
    poiDistance = '80';
    includeCloserHotel = false;

    class FakeMap {
      on(event: string, handler: (event?: unknown) => void) {
        if (event === 'click') mapClickHandler = handler;
      }
      off() {}
      destroy() {}
    }

    class FakeAutoComplete {
      constructor(options?: Record<string, unknown>) {
        autocompleteOptions(options);
      }

      search(keyword: string, callback: (status: string, result: unknown) => void) {
        callback('complete', {
          tips: [
            {
              id: 'tianjin-station',
              name: keyword,
              district: '天津市河北区',
              location: lngLat(117.21, 39.136),
            },
            {
              id: 'unknown-district',
              name: '未标注地点',
              location: lngLat(114.485, 38.01),
            },
          ],
        });
      }
    }

    class FakeGeocoder {
      private readonly options: Record<string, unknown> | undefined;

      constructor(options?: Record<string, unknown>) {
        this.options = options;
        geocoderOptions(options);
      }

      getAddress(position: [number, number], callback: (status: string, result: unknown) => void) {
        geocoderPositions.push(position);
        callback('complete', {
          regeocode: {
            formattedAddress: '天津市河北区天津站',
            addressComponent: {
              province: '北京市',
              city: [],
              district: '昌平区',
              township: '小汤山镇',
            },
            ...(this.options?.extensions === 'all'
              ? {
                  pois: [
                    ...(includeCloserHotel
                      ? [
                          {
                            name: '丽枫酒店(北京顺义首都机场店)',
                            distance: '20',
                            type: '住宿服务',
                          },
                        ]
                      : []),
                    {
                      name: '阿苏卫收费站(北六环出口)',
                      address: '小汤山镇',
                      distance: poiDistance,
                      type: '交通设施服务;收费站',
                    },
                  ],
                }
              : {}),
          },
        });
      }
    }

    class FakeMarker {
      on(event: string, handler: (event: unknown) => void) {
        if (event === 'click') handoffClickHandler = handler;
      }
      off() {}
      setMap() {}
      setPosition() {}
    }

    class FakeMassMarks {
      on(event: string, handler: (event: unknown) => void) {
        if (event === 'click') cameraClickHandler = handler;
      }
      off() {}
      setData() {}
      setMap() {}
    }

    class FakeInfoWindow {
      constructor(options: Record<string, unknown>) {
        infoWindowContent = options.content as HTMLElement;
      }

      open(_map: unknown, position: [number, number]) {
        infoWindowPosition = position;
      }

      close() {}
    }

    vi.mocked(loadAmap).mockResolvedValue({
      Map: FakeMap,
      Marker: FakeMarker,
      MassMarks: FakeMassMarks,
      InfoWindow: FakeInfoWindow,
      AutoComplete: FakeAutoComplete,
      Geocoder: FakeGeocoder,
    } as unknown as AmapNamespace);
  });

  it('does not limit autocomplete suggestions to Beijing', async () => {
    const adapter = new AmapMapAdapter('key', 'security-code');
    await adapter.initialize(document.createElement('div'), {
      onEndpointSelect,
      onHandoffSegmentSelect,
    });

    const suggestions = await adapter.searchPlaces('天津站');

    expect(autocompleteOptions).toHaveBeenCalledWith({ citylimit: false });
    expect(suggestions).toEqual([
      {
        id: 'tianjin-station',
        name: '天津站',
        district: '天津市河北区',
        coordinate: { lng: 117.21, lat: 39.136, coordinateSystem: 'GCJ02' },
      },
      {
        id: 'unknown-district',
        name: '未标注地点',
        district: '地区未标注',
        coordinate: { lng: 114.485, lat: 38.01, coordinateSystem: 'GCJ02' },
      },
    ]);
  });

  it('reverse geocodes map points and waits for an explicit endpoint choice', async () => {
    const adapter = new AmapMapAdapter('key', 'security-code');
    await adapter.initialize(document.createElement('div'), {
      onEndpointSelect,
      onHandoffSegmentSelect,
    });

    mapClickHandler?.({ lnglat: lngLat(117.21, 39.136) });

    await vi.waitFor(() => expect(infoWindowContent).not.toBeNull());
    expect(geocoderOptions).toHaveBeenCalledWith(undefined);
    expect(infoWindowPosition).toEqual([117.21, 39.136]);
    expect(infoWindowContent?.textContent).toContain('天津市河北区天津站');
    expect(infoWindowContent?.textContent).toContain('坐标：117.210000, 39.136000');
    expect(onEndpointSelect).not.toHaveBeenCalled();

    infoWindowContent
      ?.querySelector<HTMLButtonElement>('[data-testid="set-start-from-map"]')
      ?.click();
    expect(onEndpointSelect).toHaveBeenCalledWith(
      {
        coordinate: { lng: 117.21, lat: 39.136, coordinateSystem: 'GCJ02' },
        suggestedName: '天津市河北区天津站',
      },
      'start',
    );
  });

  it('shows camera coordinates in the single map info window', async () => {
    const adapter = new AmapMapAdapter('key', 'security-code');
    await adapter.initialize(document.createElement('div'), {
      onEndpointSelect,
      onHandoffSegmentSelect,
    });
    adapter.setCameras([
      {
        id: 'camera-1',
        lng: 116.42,
        lat: 39.93,
        address: '示例道路与示例路交叉口',
        cameraType: '拍进京证',
        directionText: '东向西',
      },
    ]);

    cameraClickHandler?.({ data: { id: 'camera-1' } });

    expect(infoWindowPosition).toEqual([116.42, 39.93]);
    expect(infoWindowContent?.dataset.testid).toBe('camera-popup');
    expect(infoWindowContent?.textContent).toContain('坐标：116.420000, 39.930000');
  });

  it('resolves a traffic landmark at the navigation handoff and builds exact AMap navigation', async () => {
    includeCloserHotel = true;
    const adapter = new AmapMapAdapter('key', 'security-code');
    await adapter.initialize(document.createElement('div'), {
      onEndpointSelect,
      onHandoffSegmentSelect,
    });
    adapter.setHandoffMarker({
      crossing: {
        portalId: 'P0001',
        roadName: '立汤路',
        direction: 'OUTBOUND',
        boundaryRole: 'OUTER_EXIT',
        wgs84: { lng: 116.5135, lat: 40.0185 },
        gcj02: { lng: 116.52, lat: 40.02 },
      },
      navigationHandoff: {
        wgs84: { lng: 116.5235, lat: 40.0225 },
        gcj02: { lng: 116.53, lat: 40.024 },
        boundaryClearanceMeters: 320,
        roadName: '立汤路辅路',
      },
      externalHandoff: {
        wgs84: { lng: 116.5285, lat: 40.0235 },
        gcj02: { lng: 116.535, lat: 40.025 },
        boundaryClearanceMeters: 260,
        poiSearchRadiusMeters: 200,
      },
      outerEndpoint: { lng: 117.21, lat: 39.136 },
    });

    expect(geocoderOptions).toHaveBeenLastCalledWith({ extensions: 'all', radius: 200 });
    expect(geocoderPositions.at(-1)).toEqual([116.53, 40.024]);
    handoffClickHandler?.({});
    await vi.waitFor(() =>
      expect(infoWindowContent?.textContent).toContain(
        '北京市昌平区小汤山镇阿苏卫收费站(北六环出口)附近',
      ),
    );
    expect(infoWindowContent?.textContent).not.toContain('丽枫酒店');
    expect(infoWindowPosition).toEqual([116.53, 40.024]);
    const navigationLink = infoWindowContent?.querySelector<HTMLAnchorElement>(
      '[data-testid="open-amap-navigation"]',
    );
    const navigationUrl = new URL(navigationLink?.href ?? '');
    expect(navigationUrl.origin + navigationUrl.pathname).toBe('https://uri.amap.com/navigation');
    expect(navigationUrl.searchParams.get('from')).toBe('116.53,40.024,环内路线交接点');
    expect(navigationUrl.searchParams.get('to')).toBe('117.21,39.136,环外行程终点');
    expect(navigationUrl.searchParams.get('callnative')).toBe('1');
    infoWindowContent
      ?.querySelector<HTMLButtonElement>('[data-testid="show-safe-segment"]')
      ?.click();
    expect(onHandoffSegmentSelect).toHaveBeenCalledOnce();
  });

  it('ignores a POI outside the navigation-landmark radius and reverses inbound navigation', async () => {
    poiDistance = '240';
    const adapter = new AmapMapAdapter('key', 'security-code');
    await adapter.initialize(document.createElement('div'), {
      onEndpointSelect,
      onHandoffSegmentSelect,
    });
    adapter.setHandoffMarker({
      crossing: {
        portalId: 'P0002',
        direction: 'INBOUND',
        boundaryRole: 'INNER_ENTRY',
        wgs84: { lng: 116.5135, lat: 40.0185 },
        gcj02: { lng: 116.52, lat: 40.02 },
      },
      navigationHandoff: {
        wgs84: { lng: 116.5235, lat: 40.0225 },
        gcj02: { lng: 116.53, lat: 40.024 },
        boundaryClearanceMeters: 320,
        roadName: '沙河路',
      },
      externalHandoff: {
        wgs84: { lng: 116.5285, lat: 40.0235 },
        gcj02: { lng: 116.535, lat: 40.025 },
        boundaryClearanceMeters: 260,
        poiSearchRadiusMeters: 200,
      },
      outerEndpoint: { lng: 117.21, lat: 39.136 },
    });

    handoffClickHandler?.({});
    await vi.waitFor(() =>
      expect(infoWindowContent?.textContent).toContain('天津市河北区天津站附近'),
    );
    expect(infoWindowContent?.textContent).not.toContain('马坊收费站');
    const navigationLink = infoWindowContent?.querySelector<HTMLAnchorElement>(
      '[data-testid="open-amap-navigation"]',
    );
    const navigationUrl = new URL(navigationLink?.href ?? '');
    expect(navigationUrl.searchParams.get('from')).toBe('117.21,39.136,环外行程起点');
    expect(navigationUrl.searchParams.get('to')).toBe('116.53,40.024,环内路线交接点');
  });
});
