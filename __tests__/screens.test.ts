import { groupApps } from '../src/features/onboarding/appGrouping';
import {
  isLiveSession,
  monitoringStatus,
} from '../src/features/today/todayModel';
import { sessionTag } from '../src/features/history/HistoryScreen';
import {
  parseSummary,
  parseLaunchableApps,
} from '../src/services/intentCheckpoint';
import { formatClock, formatMinutes, localDayBounds } from '../src/utils/time';
import type { LaunchableApp, SessionRecord } from '../src/native/types';

jest.mock('../src/native/NativeIntentCheckpoint', () => ({
  __esModule: true,
  default: {},
}));

const app = (
  p: string,
  label: string,
  category: LaunchableApp['category'],
  sensitive = false,
): LaunchableApp => ({
  packageName: p,
  label,
  category,
  sensitive,
  icon: null,
});

describe('groupApps', () => {
  const apps = [
    app('com.instagram.android', 'Instagram', 'social'),
    app('com.google.android.youtube', 'YouTube', 'video'),
    app('com.phonepe.app', 'PhonePe', 'other', true),
    app('com.example.notes', 'Notes', 'other'),
  ];

  it('orders categories and puts sensitive apps last', () => {
    expect(groupApps(apps, '').map(s => s.key)).toEqual([
      'social',
      'video',
      'other',
      'sensitive',
    ]);
  });

  it('searches label and package name', () => {
    expect(groupApps(apps, 'tube')[0].data[0].label).toBe('YouTube');
    expect(groupApps(apps, 'com.instagram')[0].data).toHaveLength(1);
    expect(groupApps(apps, 'zzz')).toEqual([]);
  });
});

describe('today model', () => {
  const p = {
    usageAccess: true,
    overlay: true,
    notifications: true,
    batteryUnrestricted: true,
    exactAlarms: true,
  };
  it('status reflects permissions first, then the service', () => {
    expect(
      monitoringStatus(
        { ...p, overlay: false },
        { enabled: true, running: true, monitoredPackages: [] },
      ).tone,
    ).toBe('attention');
    expect(
      monitoringStatus(p, {
        enabled: true,
        running: true,
        monitoredPackages: [],
      }).label,
    ).toBe('Active monitoring');
    expect(
      monitoringStatus(p, {
        enabled: false,
        running: false,
        monitoredPackages: [],
      }).tone,
    ).toBe('paused');
  });

  const base: SessionRecord = {
    id: 's',
    packageName: 'p',
    appName: 'A',
    intention: 'x',
    startedAt: 1,
    plannedDurationSeconds: 60,
    plannedEndAt: 2,
    endedAt: null,
    wallClockSeconds: null,
    foregroundSeconds: 0,
    extensionCount: 0,
    state: 'SESSION_ACTIVE',
    completionReason: null,
    createdAt: 1,
    updatedAt: 1,
  };
  it('live sessions exclude checkpoints and finished sessions', () => {
    expect(isLiveSession(base)).toBe(true);
    expect(isLiveSession({ ...base, state: 'BACKGROUND_GRACE' })).toBe(true);
    expect(
      isLiveSession({ ...base, state: 'AWAITING_INTENTION', startedAt: null }),
    ).toBe(false);
    expect(isLiveSession({ ...base, state: 'SESSION_COMPLETED' })).toBe(false);
    expect(isLiveSession(null)).toBe(false);
  });

  it('history tags', () => {
    expect(
      sessionTag({ ...base, startedAt: null, state: 'SESSION_ABANDONED' }),
    ).toBe('Not now');
    expect(
      sessionTag({
        ...base,
        state: 'SESSION_COMPLETED',
        completionReason: 'done',
      }),
    ).toBe('Done');
    expect(
      sessionTag({
        ...base,
        state: 'SESSION_ABANDONED',
        completionReason: 'device_restarted',
      }),
    ).toBe('Phone restarted');
  });
});

describe('parsers', () => {
  it('summary defaults malformed fields to zero', () => {
    const s = parseSummary({
      opensNoticed: 3,
      perApp: [{ packageName: 'a', appName: 'A', opens: 3 }],
    });
    expect(s.opensNoticed).toBe(3);
    expect(s.plannedSeconds).toBe(0);
    expect(s.perApp[0].actualSeconds).toBe(0);
  });

  it('launchable apps drop malformed rows and unknown categories', () => {
    const list = parseLaunchableApps([
      { packageName: 'a', label: 'A', category: 'weird' },
      { label: 'no pkg' },
    ]);
    expect(list).toHaveLength(1);
    expect(list[0].category).toBe('other');
  });
});

describe('time helpers', () => {
  it('formats', () => {
    expect(formatMinutes(59)).toBe('0 min');
    expect(formatMinutes(420)).toBe('7 min');
    expect(formatMinutes(3900)).toBe('1 h 5 min');
    expect(formatClock(522_000)).toBe('08:42');
    expect(formatClock(-5)).toBe('00:00');
  });

  it('day bounds are 24h or the local DST length and contain now', () => {
    const now = Date.now();
    const { start, end } = localDayBounds(now);
    expect(start).toBeLessThanOrEqual(now);
    expect(end).toBeGreaterThan(now);
    expect(new Date(start).getHours()).toBe(0);
  });
});
