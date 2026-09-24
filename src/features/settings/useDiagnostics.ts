import { useCallback, useEffect, useRef, useState } from 'react';
import { PermissionsAndroid, Platform } from 'react-native';
import { useAppActive } from '../../hooks/useAppActive';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import type {
  LatencySummary,
  MonitoringStatus,
  NativeEvent,
  PermissionState,
  SessionRecord,
} from '../../native/types';

export interface LogEntry {
  at: number;
  text: string;
}

const MAX_LOG = 40;

/** Log line for an engine event. Never includes intention text (PRD §66). */
export function describeEvent(e: NativeEvent): string {
  switch (e.type) {
    case 'foregroundAppChanged':
      return `foreground → ${e.packageName ?? '(screen off)'}`;
    case 'sessionStateChanged':
      return `${e.session.appName}: ${e.session.state}${
        e.session.completionReason ? ` (${e.session.completionReason})` : ''
      }`;
    case 'timerExpired':
      return 'timer expired';
    case 'permissionChanged':
      return `permissions: usage=${e.state.usageAccess} overlay=${e.state.overlay}`;
    case 'sessionRestored':
      return `engine restored (${
        e.session ? e.session.state : 'no open session'
      })`;
    case 'monitoringStarted':
    case 'monitoringStopped':
      return e.type;
  }
}

/**
 * Diagnostics state (Settings → Diagnostics). Everything here is a read-through of native state; the only local
 * state is the event log (ephemeral by design).
 */
export function useDiagnostics() {
  const [permissions, setPermissions] = useState<PermissionState | null>(null);
  const [status, setStatus] = useState<MonitoringStatus | null>(null);
  const [session, setSession] = useState<SessionRecord | null>(null);
  const [latency, setLatency] = useState<LatencySummary | null>(null);
  const [log, setLog] = useState<LogEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);

  const guard = useCallback(async (fn: () => Promise<void>) => {
    try {
      setError(null);
      await fn();
    } catch (e) {
      if (mounted.current) setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const refresh = useCallback(
    () =>
      guard(async () => {
        const [p, s, a, l] = await Promise.all([
          intentCheckpoint.getPermissionState(),
          intentCheckpoint.getMonitoringStatus(),
          intentCheckpoint.getActiveSession(),
          intentCheckpoint.getLatencyStats(),
        ]);
        if (!mounted.current) return;
        setPermissions(p);
        setStatus(s);
        setSession(a);
        setLatency(l);
      }),
    [guard],
  );

  useAppActive(refresh);

  useEffect(() => {
    mounted.current = true;
    refresh();
    const unsubscribe = intentCheckpoint.subscribe(event => {
      setLog(prev =>
        [{ at: Date.now(), text: describeEvent(event) }, ...prev].slice(
          0,
          MAX_LOG,
        ),
      );
      if (event.type === 'permissionChanged') setPermissions(event.state);
      if (
        event.type === 'sessionStateChanged' ||
        event.type === 'sessionRestored'
      ) {
        // Re-read rather than trusting event order: the engine decides which session is active.
        intentCheckpoint.getActiveSession().then(
          s => mounted.current && setSession(s),
          () => {},
        );
      }
      if (event.type === 'foregroundAppChanged') {
        intentCheckpoint.getLatencyStats().then(
          l => mounted.current && setLatency(l),
          () => {},
        );
      }
      if (
        event.type === 'monitoringStarted' ||
        event.type === 'monitoringStopped'
      ) {
        intentCheckpoint.getMonitoringStatus().then(
          s => mounted.current && setStatus(s),
          () => {},
        );
      }
    });
    return () => {
      mounted.current = false;
      unsubscribe();
    };
  }, [refresh]);

  const requestNotifications = useCallback(
    () =>
      guard(async () => {
        if (Platform.OS === 'android' && Platform.Version >= 33) {
          await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
          );
        }
        await refresh();
      }),
    [guard, refresh],
  );

  const toggleMonitoring = useCallback(
    () =>
      guard(async () => {
        if (status?.enabled) await intentCheckpoint.stopMonitoring();
        else await intentCheckpoint.startMonitoring();
        // The service starts asynchronously; the monitoringStarted event refreshes status too.
        setTimeout(refresh, 500);
      }),
    [guard, refresh, status?.enabled],
  );

  return {
    permissions,
    status,
    session,
    latency,
    log,
    error,
    refresh,
    requestNotifications,
    toggleMonitoring,
    completeSession: (reason: 'done' | 'left') =>
      session
        ? guard(() => intentCheckpoint.completeSession(session.id, reason))
        : undefined,
    clearLatency: () =>
      guard(async () => {
        await intentCheckpoint.clearLatencyStats();
        await refresh();
      }),
  };
}
