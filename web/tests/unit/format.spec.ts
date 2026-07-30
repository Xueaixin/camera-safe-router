import { describe, expect, it } from 'vitest';

import { formatDistance, formatDuration, formatSnapshotVersion } from '@/utils/format';

describe('route formatters', () => {
  it('formats meters and kilometers without layout-noisy precision', () => {
    expect(formatDistance(820)).toBe('820 米');
    expect(formatDistance(12640.5)).toBe('13 公里');
    expect(formatDistance(-1)).toBe('--');
  });

  it('formats durations around hour boundaries', () => {
    expect(formatDuration(1)).toBe('1 分钟');
    expect(formatDuration(1680)).toBe('28 分钟');
    expect(formatDuration(5400)).toBe('1 小时 30 分钟');
  });

  it('keeps non-date snapshot versions intact', () => {
    expect(formatSnapshotVersion('fixture-camera-v1')).toBe('fixture-camera-v1');
  });
});
