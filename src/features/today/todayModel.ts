import type { StatusTone } from '../../components/ui';
import type {
  MonitoringStatus,
  PermissionState,
  SessionRecord,
} from '../../native/types';

export function monitoringStatus(
  permissions: PermissionState | null,
  status: MonitoringStatus | null,
): { tone: StatusTone; label: string } {
  if (permissions && (!permissions.usageAccess || !permissions.overlay)) {
    return { tone: 'attention', label: 'Needs attention' };
  }
  if (status?.running) return { tone: 'ok', label: 'Active monitoring' };
  if (status?.enabled) return { tone: 'attention', label: 'Starting…' };
  return { tone: 'paused', label: 'Paused' };
}

/** A session the user committed to and that is still open (in the app or within grace). */
export function isLiveSession(s: SessionRecord | null): s is SessionRecord {
  return (
    !!s &&
    s.startedAt != null &&
    (s.state === 'SESSION_ACTIVE' ||
      s.state === 'BACKGROUND_GRACE' ||
      s.state === 'TIME_EXPIRED' ||
      s.state === 'EXTENSION_REQUEST')
  );
}

export function sessionStateLabel(s: SessionRecord): string {
  switch (s.state) {
    case 'SESSION_ACTIVE':
      return 'In the app';
    case 'BACKGROUND_GRACE':
      return 'Away — continues if you return soon';
    case 'TIME_EXPIRED':
    case 'EXTENSION_REQUEST':
      return "Time's up";
    default:
      return '';
  }
}
