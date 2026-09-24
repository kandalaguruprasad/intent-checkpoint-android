/**
 * App-facing types mirroring the Kotlin models (android/intent-core). Kotlin is the source of
 * truth; these are read-only views of what it persisted (PRD §25, §33).
 */

export type SessionState =
  | 'PAUSE'
  | 'AWAITING_INTENTION'
  | 'SESSION_ACTIVE'
  | 'BACKGROUND_GRACE'
  | 'TIME_EXPIRED'
  | 'EXTENSION_REQUEST'
  | 'SESSION_COMPLETED'
  | 'SESSION_ABANDONED';

export type CompletionReason =
  | 'done'
  | 'left'
  | 'abandoned'
  | 'device_restarted';

/** Reasons RN may request. `device_restarted` is reserved for the native boot sweep. */
export type RequestableCompletionReason = Exclude<
  CompletionReason,
  'device_restarted'
>;

export interface SessionRecord {
  id: string;
  packageName: string;
  appName: string;
  intention: string;
  startedAt: number | null;
  /** null = "No timer". */
  plannedDurationSeconds: number | null;
  plannedEndAt: number | null;
  endedAt: number | null;
  wallClockSeconds: number | null;
  /** Time the app was actually in front, including a running interval at read time. */
  foregroundSeconds: number;
  extensionCount: number;
  state: SessionState;
  completionReason: CompletionReason | null;
  createdAt: number;
  updatedAt: number;
}

export interface PermissionState {
  usageAccess: boolean;
  overlay: boolean;
  notifications: boolean;
  /** Best-effort signal only; aggressive OEMs can still kill the service. */
  batteryUnrestricted: boolean;
  exactAlarms: boolean;
}

export interface LatencySummary {
  count: number;
  medianMs: number | null;
  p95Ms: number | null;
  maxMs: number | null;
}

export interface MonitoringStatus {
  enabled: boolean;
  running: boolean;
  monitoredPackages: string[];
}

export interface AppDay {
  packageName: string;
  appName: string;
  opens: number;
  actualSeconds: number;
}

/** Derived natively from persisted sessions only (PRD §50). */
export interface DailySummary {
  opensNoticed: number;
  intentionalSessions: number;
  choseNotToOpen: number;
  plannedSeconds: number;
  actualSecondsTimed: number;
  actualSecondsAll: number;
  extensions: number;
  finishedOnTime: number;
  perApp: AppDay[];
}

export type AppCategory =
  | 'social'
  | 'video'
  | 'games'
  | 'shopping'
  | 'news'
  | 'browsers'
  | 'other';

export interface LaunchableApp {
  packageName: string;
  label: string;
  category: AppCategory;
  /** Banking / payment / authenticator / password heuristic. */
  sensitive: boolean;
  /** PNG data URI or null. */
  icon: string | null;
}

export interface MonitoredApp {
  packageName: string;
  appName: string;
  category: AppCategory;
  icon: string | null;
}

export type OverlayPreviewKind =
  | 'pause'
  | 'intention'
  | 'reminder'
  | 'reminder-warning'
  | 'reminder-no-timer'
  | 'timesup'
  | 'extension'
  | 'complete'
  | 'hide';

export type NativeEvent =
  | { type: 'foregroundAppChanged'; packageName: string | null }
  | { type: 'monitoringStarted' }
  | { type: 'monitoringStopped' }
  | { type: 'permissionChanged'; state: PermissionState }
  | { type: 'sessionStateChanged'; session: SessionRecord }
  | { type: 'timerExpired'; sessionId: string }
  | { type: 'sessionRestored'; session: SessionRecord | null };

export type NativeErrorCode =
  | 'E_PERMISSION_DENIED'
  | 'E_SESSION_NOT_FOUND'
  | 'E_INVALID_STATE'
  | 'E_INVALID_ARGUMENT'
  | 'E_START_NOT_ALLOWED'
  | 'E_SETTINGS_UNAVAILABLE'
  | 'E_ENGINE_FAILURE';
