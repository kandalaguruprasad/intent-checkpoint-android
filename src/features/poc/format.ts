export function formatRemaining(
  plannedEndAt: number | null,
  now: number,
): string {
  if (plannedEndAt == null) return 'no timer';
  const total = Math.max(0, Math.ceil((plannedEndAt - now) / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')} left`;
}

export function formatMs(ms: number | null): string {
  if (ms == null) return '—';
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms} ms`;
}

/** PRD §61 GO thresholds: median < 2 s, p95 < 4 s. */
export function latencyVerdict(
  medianMs: number | null,
  p95Ms: number | null,
): 'go' | 'no-go' | 'insufficient' {
  if (medianMs == null || p95Ms == null) return 'insufficient';
  return medianMs < 2000 && p95Ms < 4000 ? 'go' : 'no-go';
}
