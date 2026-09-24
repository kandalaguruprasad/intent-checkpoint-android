import {
  parseNativeEvent,
  parseSession,
} from '../src/services/intentCheckpoint';
import { describeEvent } from '../src/features/settings/useDiagnostics';
import {
  formatRemaining,
  latencyVerdict,
} from '../src/features/settings/format';

jest.mock('../src/native/NativeIntentCheckpoint', () => ({
  __esModule: true,
  default: {},
}));

const raw = {
  id: 's1',
  packageName: 'com.instagram.android',
  appName: 'Instagram',
  intention: 'Reply to Ravi',
  startedAt: 1_700_000_000_000,
  plannedDurationSeconds: 600,
  plannedEndAt: 1_700_000_600_000,
  endedAt: null,
  wallClockSeconds: null,
  foregroundSeconds: 0,
  extensionCount: 0,
  state: 'SESSION_ACTIVE',
  completionReason: null,
  createdAt: 1_700_000_000_000,
  updatedAt: 1_700_000_000_000,
};

describe('parseSession', () => {
  it('accepts a well-formed engine payload', () => {
    expect(parseSession(raw)).toEqual(raw);
  });

  it('rejects unknown states and missing ids', () => {
    expect(parseSession({ ...raw, state: 'LOCKED' })).toBeNull();
    expect(parseSession({ ...raw, id: undefined })).toBeNull();
    expect(parseSession(null)).toBeNull();
  });

  it('normalises unknown completion reasons to null', () => {
    expect(
      parseSession({ ...raw, completionReason: 'force_closed' })
        ?.completionReason,
    ).toBeNull();
  });
});

describe('parseNativeEvent', () => {
  it('parses each event type', () => {
    expect(
      parseNativeEvent({ type: 'foregroundAppChanged', packageName: null }),
    ).toEqual({
      type: 'foregroundAppChanged',
      packageName: null,
    });
    expect(parseNativeEvent({ type: 'timerExpired', sessionId: 's1' })).toEqual(
      {
        type: 'timerExpired',
        sessionId: 's1',
      },
    );
    expect(
      parseNativeEvent({ type: 'sessionStateChanged', session: raw }),
    ).toEqual({
      type: 'sessionStateChanged',
      session: raw,
    });
    expect(
      parseNativeEvent({ type: 'sessionRestored', session: null }),
    ).toEqual({
      type: 'sessionRestored',
      session: null,
    });
  });

  it('drops malformed or unknown events', () => {
    expect(
      parseNativeEvent({ type: 'sessionStateChanged', session: {} }),
    ).toBeNull();
    expect(parseNativeEvent({ type: 'nope' })).toBeNull();
    expect(parseNativeEvent('x')).toBeNull();
  });
});

describe('describeEvent', () => {
  it('never includes intention text in the log line', () => {
    const line = describeEvent({
      type: 'sessionStateChanged',
      session: parseSession(raw)!,
    });
    expect(line).not.toContain('Ravi');
    expect(line).toContain('SESSION_ACTIVE');
  });
});

describe('format', () => {
  it('formats remaining time', () => {
    expect(formatRemaining(null, 0)).toBe('no timer');
    expect(formatRemaining(90_500, 0)).toBe('01:31 left');
    expect(formatRemaining(0, 5_000)).toBe('00:00 left');
  });

  it('applies the PRD GO thresholds', () => {
    expect(latencyVerdict(null, null)).toBe('insufficient');
    expect(latencyVerdict(1_500, 3_900)).toBe('go');
    expect(latencyVerdict(2_000, 3_000)).toBe('no-go');
    expect(latencyVerdict(1_000, 4_000)).toBe('no-go');
  });
});
