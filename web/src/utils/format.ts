export function formatDistance(meters: number): string {
  if (!Number.isFinite(meters) || meters < 0) return '--';
  if (meters < 1000) return `${Math.round(meters)} 米`;
  const kilometers = meters / 1000;
  return `${kilometers < 10 ? kilometers.toFixed(1) : Math.round(kilometers)} 公里`;
}

export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '--';
  const minutes = Math.max(1, Math.round(seconds / 60));
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  return remainder === 0 ? `${hours} 小时` : `${hours} 小时 ${remainder} 分钟`;
}

export function formatSnapshotVersion(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}
