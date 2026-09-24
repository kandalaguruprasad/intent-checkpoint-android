/** Local-day window [start, end) in epoch ms, following the device timezone. */
export function localDayBounds(
  nowMs: number,
  daysAgo = 0,
): { start: number; end: number } {
  const d = new Date(nowMs);
  const start = new Date(
    d.getFullYear(),
    d.getMonth(),
    d.getDate() - daysAgo,
  ).getTime();
  const end = new Date(
    d.getFullYear(),
    d.getMonth(),
    d.getDate() - daysAgo + 1,
  ).getTime();
  return { start, end };
}

/** "0 min", "7 min", "1 h 5 min". Rounds down: never overstates time spent. */
export function formatMinutes(seconds: number): string {
  const m = Math.floor(Math.max(0, seconds) / 60);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest === 0 ? `${h} h` : `${h} h ${rest} min`;
}

/** mm:ss, rounding up so 00:00 only appears at true expiry (matches the native pill). */
export function formatClock(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(
    total % 60,
  ).padStart(2, '0')}`;
}

export function formatTimeOfDay(ms: number): string {
  const d = new Date(ms);
  return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function dayLabel(dayStartMs: number, nowMs: number): string {
  const today = localDayBounds(nowMs).start;
  if (dayStartMs === today) return 'Today';
  if (dayStartMs === localDayBounds(nowMs, 1).start) return 'Yesterday';
  return new Date(dayStartMs).toDateString().slice(0, 10);
}
