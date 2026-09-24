import { useCallback, useEffect, useRef, useState } from 'react';
import { useAppActive } from '../../hooks/useAppActive';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import type {
  DailySummary,
  MonitoredApp,
  MonitoringStatus,
  NativeEvent,
  PermissionState,
  SessionRecord,
} from '../../native/types';
import { localDayBounds } from '../../utils/time';
import {
  fixtureActiveSession,
  fixtureMonitored,
  fixtureSummary,
} from '../../fixtures/designFixtures';
import { usePreview } from './preview';

/** Subscribes to engine events for the lifetime of the component. */
export function useEngineEvents(listener: (e: NativeEvent) => void): void {
  const ref = useRef(listener);
  ref.current = listener;
  useEffect(() => intentCheckpoint.subscribe(e => ref.current(e)), []);
}

export interface TodayData {
  loading: boolean;
  error: string | null;
  summary: DailySummary | null;
  session: SessionRecord | null;
  monitored: MonitoredApp[];
  permissions: PermissionState | null;
  status: MonitoringStatus | null;
  refresh: () => void;
}

/**
 * Everything the Today screen shows, read from the engine (SQLite) on focus, on app resume and on
 * every session/permission event. In preview mode, fixture data instead.
 */
export function useToday(): TodayData {
  const { fixtures } = usePreview();
  const [state, setState] = useState<Omit<TodayData, 'refresh'>>({
    loading: true,
    error: null,
    summary: null,
    session: null,
    monitored: [],
    permissions: null,
    status: null,
  });
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    if (fixtures) {
      setState({
        loading: false,
        error: null,
        summary: fixtureSummary,
        session: fixtureActiveSession,
        monitored: fixtureMonitored,
        permissions: {
          usageAccess: true,
          overlay: true,
          notifications: true,
          batteryUnrestricted: true,
          exactAlarms: true,
        },
        status: { enabled: true, running: true, monitoredPackages: [] },
      });
      return;
    }
    try {
      const { start, end } = localDayBounds(Date.now());
      const [summary, session, monitored, permissions, status] =
        await Promise.all([
          intentCheckpoint.getSummary(start, end),
          intentCheckpoint.getActiveSession(),
          intentCheckpoint.getMonitoredApps(),
          intentCheckpoint.getPermissionState(),
          intentCheckpoint.getMonitoringStatus(),
        ]);
      if (!mounted.current) return;
      setState({
        loading: false,
        error: null,
        summary,
        session,
        monitored,
        permissions,
        status,
      });
    } catch (e) {
      if (!mounted.current) return;
      setState(s => ({
        ...s,
        loading: false,
        error: e instanceof Error ? e.message : String(e),
      }));
    }
  }, [fixtures]);

  useEffect(() => {
    mounted.current = true;
    refresh();
    return () => {
      mounted.current = false;
    };
  }, [refresh]);

  useAppActive(refresh);
  useEngineEvents(e => {
    if (
      e.type === 'sessionStateChanged' ||
      e.type === 'permissionChanged' ||
      e.type === 'monitoringStarted' ||
      e.type === 'monitoringStopped' ||
      e.type === 'sessionRestored'
    ) {
      refresh();
    }
  });

  return { ...state, refresh };
}
