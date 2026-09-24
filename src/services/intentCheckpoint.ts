/**
 * Typed wrapper around the IntentCheckpoint TurboModule. The only module in the app that talks to
 * native; screens and hooks import from here. Holds no session state of its own (ADR-006).
 */
import { NativeEventEmitter, Platform } from 'react-native';
import NativeIntentCheckpoint from '../native/NativeIntentCheckpoint';
import type {
  LatencySummary,
  MonitoringStatus,
  NativeErrorCode,
  NativeEvent,
  PermissionState,
  RequestableCompletionReason,
  SessionRecord,
  SessionState,
} from '../native/types';

export const NATIVE_EVENT_NAME = 'IntentCheckpointEvent';

const SESSION_STATES: ReadonlySet<SessionState> = new Set([
  'PAUSE',
  'AWAITING_INTENTION',
  'SESSION_ACTIVE',
  'BACKGROUND_GRACE',
  'TIME_EXPIRED',
  'EXTENSION_REQUEST',
  'SESSION_COMPLETED',
  'SESSION_ABANDONED',
]);

export class NativeModuleError extends Error {
  constructor(readonly code: NativeErrorCode | string, message: string) {
    super(message);
    this.name = 'NativeModuleError';
  }
}

const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null;

const numOrNull = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null;

/** Validates the shape coming over the bridge; returns null for anything malformed. */
export function parseSession(raw: unknown): SessionRecord | null {
  if (!isObject(raw)) return null;
  const { id, packageName, appName, intention, state } = raw;
  if (
    typeof id !== 'string' ||
    typeof packageName !== 'string' ||
    typeof appName !== 'string' ||
    typeof intention !== 'string' ||
    typeof state !== 'string' ||
    !SESSION_STATES.has(state as SessionState)
  ) {
    return null;
  }
  const reason = raw.completionReason;
  return {
    id,
    packageName,
    appName,
    intention,
    state: state as SessionState,
    startedAt: numOrNull(raw.startedAt),
    plannedDurationSeconds: numOrNull(raw.plannedDurationSeconds),
    plannedEndAt: numOrNull(raw.plannedEndAt),
    endedAt: numOrNull(raw.endedAt),
    wallClockSeconds: numOrNull(raw.wallClockSeconds),
    extensionCount: numOrNull(raw.extensionCount) ?? 0,
    completionReason:
      reason === 'done' ||
      reason === 'left' ||
      reason === 'abandoned' ||
      reason === 'device_restarted'
        ? reason
        : null,
    createdAt: numOrNull(raw.createdAt) ?? 0,
    updatedAt: numOrNull(raw.updatedAt) ?? 0,
  };
}

function parsePermissions(raw: unknown): PermissionState | null {
  if (!isObject(raw)) return null;
  return {
    usageAccess: raw.usageAccess === true,
    overlay: raw.overlay === true,
    notifications: raw.notifications === true,
    batteryUnrestricted: raw.batteryUnrestricted === true,
    exactAlarms: raw.exactAlarms === true,
  };
}

export function parseNativeEvent(raw: unknown): NativeEvent | null {
  if (!isObject(raw) || typeof raw.type !== 'string') return null;
  switch (raw.type) {
    case 'foregroundAppChanged':
      return {
        type: raw.type,
        packageName:
          typeof raw.packageName === 'string' ? raw.packageName : null,
      };
    case 'monitoringStarted':
    case 'monitoringStopped':
      return { type: raw.type };
    case 'permissionChanged': {
      const state = parsePermissions(raw.state);
      return state ? { type: raw.type, state } : null;
    }
    case 'sessionStateChanged': {
      const session = parseSession(raw.session);
      return session ? { type: raw.type, session } : null;
    }
    case 'timerExpired':
      return typeof raw.sessionId === 'string'
        ? { type: raw.type, sessionId: raw.sessionId }
        : null;
    case 'sessionRestored':
      return { type: raw.type, session: parseSession(raw.session) };
    default:
      return null;
  }
}

async function call<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (e) {
    const err = e as { code?: unknown; message?: unknown };
    throw new NativeModuleError(
      typeof err.code === 'string' ? err.code : 'E_ENGINE_FAILURE',
      typeof err.message === 'string' ? err.message : 'Native call failed',
    );
  }
}

let emitter: NativeEventEmitter | null = null;

export const intentCheckpoint = {
  isSupported: Platform.OS === 'android',

  startMonitoring: () => call(() => NativeIntentCheckpoint.startMonitoring()),
  stopMonitoring: () => call(() => NativeIntentCheckpoint.stopMonitoring()),
  getMonitoringStatus: (): Promise<MonitoringStatus> =>
    call(() => NativeIntentCheckpoint.getMonitoringStatus()),
  getCurrentForegroundApp: () =>
    call(() => NativeIntentCheckpoint.getCurrentForegroundApp()),

  getPermissionState: (): Promise<PermissionState> =>
    call(() => NativeIntentCheckpoint.getPermissionState()),
  openUsageAccessSettings: () =>
    call(() => NativeIntentCheckpoint.openUsageAccessSettings()),
  openOverlaySettings: () =>
    call(() => NativeIntentCheckpoint.openOverlaySettings()),
  openBatteryOptimizationSettings: () =>
    call(() => NativeIntentCheckpoint.openBatteryOptimizationSettings()),
  openExactAlarmSettings: () =>
    call(() => NativeIntentCheckpoint.openExactAlarmSettings()),

  getActiveSession: async (): Promise<SessionRecord | null> =>
    parseSession(await call(() => NativeIntentCheckpoint.getActiveSession())),
  submitIntention: (
    sessionId: string,
    intention: string,
    plannedSeconds: number | null,
  ) =>
    call(() =>
      NativeIntentCheckpoint.submitIntention(
        sessionId,
        intention,
        plannedSeconds,
      ),
    ),
  requestExtension: (
    sessionId: string,
    reason: string | null,
    extraSeconds: number,
  ) =>
    call(() =>
      NativeIntentCheckpoint.requestExtension(sessionId, reason, extraSeconds),
    ),
  completeSession: (sessionId: string, reason: RequestableCompletionReason) =>
    call(() => NativeIntentCheckpoint.completeSession(sessionId, reason)),

  getLatencyStats: (): Promise<LatencySummary> =>
    call(() => NativeIntentCheckpoint.getLatencyStats()),
  clearLatencyStats: () =>
    call(() => NativeIntentCheckpoint.clearLatencyStats()),

  /** Subscribes to engine events; malformed payloads are dropped. Returns an unsubscribe fn. */
  subscribe(listener: (event: NativeEvent) => void): () => void {
    emitter ??= new NativeEventEmitter(NativeIntentCheckpoint);
    const sub = emitter.addListener(NATIVE_EVENT_NAME, raw => {
      const event = parseNativeEvent(raw);
      if (event) listener(event);
    });
    return () => sub.remove();
  },
};
