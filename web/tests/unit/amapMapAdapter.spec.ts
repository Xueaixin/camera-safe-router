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
  let mapClickHandler: ((event?: unknown) => void) | null;
  let cameraClickHandler: ((event: unknown) => void) | null;
  let infoWindowContent: HTMLElement | null;
  let infoWindowPosition: [number, number] | null;

  beforeEach(() => {
    vi.clearAllMocks();
    mapClickHandler = null;
    cameraClickHandler = null;
    infoWindowContent = null;
    infoWindowPosition = null;

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
      constructor(options?: Record<string, unknown>) {
        geocoderOptions(options);
      }

      getAddress(_position: [number, number], callback: (status: string, result: unknown) => void) {
        callback('complete', { regeocode: { formattedAddress: '天津市河北区天津站' } });
      }
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
    await adapter.initialize(document.createElement('div'), { onEndpointSelect });
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
});
