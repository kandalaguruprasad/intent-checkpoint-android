/**
 * Codegen spec for the Kotlin TurboModule (PRD §25, ADR-008). Keep it flat and codegen-friendly;
 * the typed, app-facing wrapper lives in src/services/intentCheckpoint.ts.
 *
 * Session and event payloads are exchanged as plain objects produced by the Kotlin engine, which
 * is the single source of truth for session state. RN never writes session state directly.
 */
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativePermissionState = {
  usageAccess: boolean;
  overlay: boolean;
  notifications: boolean;
  /** Best-effort; OEM battery managers can still kill us when this is true. */
  batteryUnrestricted: boolean;
  /** Whether AlarmManager exact alarms are allowed (backstop for timer expiry). */
  exactAlarms: boolean;
};

export type NativeLatencySummary = {
  count: number;
  medianMs: number | null;
  p95Ms: number | null;
  maxMs: number | null;
};

export type NativeMonitoringStatus = {
  enabled: boolean;
  running: boolean;
  monitoredPackages: string[];
};

export interface Spec extends TurboModule {
  startMonitoring(): Promise<void>;
  stopMonitoring(): Promise<void>;
  getMonitoringStatus(): Promise<NativeMonitoringStatus>;
  getCurrentForegroundApp(): Promise<string | null>;
  getPermissionState(): Promise<NativePermissionState>;
  openUsageAccessSettings(): Promise<void>;
  openOverlaySettings(): Promise<void>;
  openBatteryOptimizationSettings(): Promise<void>;
  openExactAlarmSettings(): Promise<void>;
  /** Returns a SessionRecord-shaped object or null. */
  getActiveSession(): Promise<Object | null>;
  submitIntention(
    sessionId: string,
    intention: string,
    plannedSeconds: number | null,
  ): Promise<void>;
  requestExtension(
    sessionId: string,
    reason: string | null,
    extraSeconds: number,
  ): Promise<void>;
  completeSession(sessionId: string, reason: string): Promise<void>;
  /** Summary of sessions created in [fromMs, toMs); DailySummary-shaped object. */
  getSummary(fromMs: number, toMs: number): Promise<Object>;
  /** SessionRecord-shaped objects, newest first. */
  listSessions(fromMs: number, toMs: number, limit: number): Promise<Object[]>;
  /** Installed, launchable apps with category, sensitive flag and a small icon data URI. */
  listLaunchableApps(): Promise<Object[]>;
  getMonitoredApps(): Promise<Object[]>;
  setMonitoredApps(apps: Object[]): Promise<void>;
  isOnboardingComplete(): Promise<boolean>;
  setOnboardingComplete(done: boolean): Promise<void>;
  /** Renders a native overlay surface with fixture data (design preview). */
  previewOverlay(kind: string): Promise<void>;
  /** Phase 0 instrumentation (P0-011). */
  getLatencyStats(): Promise<NativeLatencySummary>;
  clearLatencyStats(): Promise<void>;

  // NativeEventEmitter contract.
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('IntentCheckpoint');
