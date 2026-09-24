import React, { useEffect, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import { formatMs, formatRemaining, latencyVerdict } from './format';
import { usePocState } from './usePocState';

const light = {
  bg: '#F7F5F2',
  card: '#FFFFFF',
  text: '#2B2A28',
  muted: '#6F6B66',
  accent: '#5B7A6A',
  onAccent: '#FFFFFF',
  warn: '#B7894A',
  chip: '#ECE8E2',
};
const dark: typeof light = {
  bg: '#1A1917',
  card: '#262422',
  text: '#ECEAE6',
  muted: '#A8A39C',
  accent: '#8FB3A0',
  onAccent: '#10201A',
  warn: '#D9B27A',
  chip: '#34312E',
};
type Palette = typeof light;

/**
 * Phase 0 diagnostics screen (PRD §60–61): permission health, start/stop monitoring, the active
 * session as the engine sees it, detection-latency stats for the GO/NO-GO gate, and a live event
 * log. Not product UI — onboarding/dashboard start in Phase 1 once the POC passes.
 */
export function PocScreen() {
  const c = useColorScheme() === 'dark' ? dark : light;
  const insets = useSafeAreaInsets();
  const s = usePocState();
  const now = useNow(s.session?.plannedEndAt != null);

  const p = s.permissions;
  const canStart = !!p?.usageAccess && !!p?.overlay;
  const verdict = latencyVerdict(
    s.latency?.medianMs ?? null,
    s.latency?.p95Ms ?? null,
  );

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[
        styles.content,
        { paddingTop: insets.top + 16, paddingBottom: insets.bottom + 24 },
      ]}
    >
      <Text
        style={[styles.title, { color: c.text }]}
        accessibilityRole="header"
      >
        Intent
      </Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Phase 0 feasibility build. Open apps with a reason.
      </Text>

      {s.error && (
        <Text
          style={[styles.error, { color: c.warn }]}
          accessibilityLiveRegion="polite"
        >
          {s.error}
        </Text>
      )}

      <Card c={c} title="Permission health">
        <PermissionRow
          c={c}
          label="Usage Access"
          why="Notices when a monitored app opens."
          ok={p?.usageAccess}
          onFix={intentCheckpoint.openUsageAccessSettings}
        />
        <PermissionRow
          c={c}
          label="Display over other apps"
          why="Shows the checkpoint and reminder."
          ok={p?.overlay}
          onFix={intentCheckpoint.openOverlaySettings}
        />
        <PermissionRow
          c={c}
          label="Notifications"
          why="Keeps the 'Intentional use is active' notice visible."
          ok={p?.notifications}
          onFix={s.requestNotifications}
        />
        <PermissionRow
          c={c}
          label="Exact alarms"
          why="Makes the timer end on time even if the phone is idle."
          ok={p?.exactAlarms}
          optional
          onFix={intentCheckpoint.openExactAlarmSettings}
        />
        <PermissionRow
          c={c}
          label="Background reliability"
          why="Some phones stop background apps. Excluding Intent helps."
          ok={p?.batteryUnrestricted}
          optional
          okLabel="Unrestricted"
          badLabel="Optimization detected"
          onFix={intentCheckpoint.openBatteryOptimizationSettings}
        />
      </Card>

      <Card c={c} title="Monitoring">
        <Text style={[styles.body, { color: c.text }]}>
          {s.status?.running
            ? 'Watching for: '
            : s.status?.enabled
            ? 'Enabled, service starting… '
            : 'Off. Will watch: '}
          {s.status?.monitoredPackages.join(', ') || '—'}
        </Text>
        <Button
          c={c}
          label={s.status?.enabled ? 'Stop monitoring' : 'Start monitoring'}
          disabled={!s.status?.enabled && !canStart}
          onPress={s.toggleMonitoring}
        />
        {!canStart && !s.status?.enabled && (
          <Text style={[styles.hint, { color: c.muted }]}>
            Grant Usage Access and Display over other apps first.
          </Text>
        )}
      </Card>

      <Card c={c} title="Active session">
        {s.session ? (
          <>
            <Text style={[styles.body, { color: c.text }]}>
              {s.session.appName} · {s.session.state}
            </Text>
            {s.session.intention !== '' && (
              <Text style={[styles.intention, { color: c.text }]}>
                “{s.session.intention}”
              </Text>
            )}
            <Text style={[styles.hint, { color: c.muted }]}>
              {formatRemaining(s.session.plannedEndAt, now)} · extensions{' '}
              {s.session.extensionCount}
            </Text>
            {s.session.startedAt != null && (
              <View style={styles.row}>
                <Button
                  c={c}
                  label="Done"
                  onPress={() => s.completeSession('done')}
                />
                <Button
                  c={c}
                  label="End"
                  secondary
                  onPress={() => s.completeSession('left')}
                />
              </View>
            )}
          </>
        ) : (
          <Text style={[styles.hint, { color: c.muted }]}>
            No open session. Open Instagram to trigger a checkpoint.
          </Text>
        )}
      </Card>

      <Card c={c} title="Detection latency (P0-011)">
        <Text style={[styles.body, { color: c.text }]}>
          samples {s.latency?.count ?? 0} · median{' '}
          {formatMs(s.latency?.medianMs ?? null)} · p95{' '}
          {formatMs(s.latency?.p95Ms ?? null)} · max{' '}
          {formatMs(s.latency?.maxMs ?? null)}
        </Text>
        <Text
          style={[
            styles.hint,
            { color: verdict === 'no-go' ? c.warn : c.muted },
          ]}
        >
          {verdict === 'insufficient'
            ? 'Open the monitored app a few times to collect samples.'
            : verdict === 'go'
            ? 'Within GO threshold (median < 2 s, p95 < 4 s).'
            : 'Outside GO threshold (median < 2 s, p95 < 4 s).'}
        </Text>
        <View style={styles.row}>
          <Button c={c} label="Refresh" secondary onPress={s.refresh} />
          <Button c={c} label="Clear" secondary onPress={s.clearLatency} />
        </View>
      </Card>

      <Card c={c} title="Engine events">
        {s.log.length === 0 ? (
          <Text style={[styles.hint, { color: c.muted }]}>Nothing yet.</Text>
        ) : (
          s.log.map((e, i) => (
            <Text key={`${e.at}-${i}`} style={[styles.log, { color: c.muted }]}>
              {new Date(e.at).toLocaleTimeString()} {e.text}
            </Text>
          ))
        )}
      </Card>
    </ScrollView>
  );
}

function useNow(ticking: boolean): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!ticking) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [ticking]);
  return now;
}

function Card({
  c,
  title,
  children,
}: {
  c: Palette;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <View style={[styles.card, { backgroundColor: c.card }]}>
      <Text
        style={[styles.cardTitle, { color: c.text }]}
        accessibilityRole="header"
      >
        {title}
      </Text>
      {children}
    </View>
  );
}

function PermissionRow(props: {
  c: Palette;
  label: string;
  why: string;
  ok: boolean | undefined;
  optional?: boolean;
  okLabel?: string;
  badLabel?: string;
  onFix: () => Promise<unknown> | unknown;
}) {
  const { c, ok } = props;
  const state =
    ok === undefined
      ? '…'
      : ok
      ? props.okLabel ?? 'Enabled'
      : props.badLabel ?? 'Not granted';
  return (
    <View style={styles.permRow}>
      <View style={styles.permText}>
        <Text style={[styles.body, { color: c.text }]}>
          {props.label}
          {props.optional ? ' (recommended)' : ''}
        </Text>
        <Text style={[styles.hint, { color: c.muted }]}>{props.why}</Text>
        <Text style={[styles.hint, { color: ok ? c.accent : c.warn }]}>
          {state}
        </Text>
      </View>
      {ok === false && (
        <Button c={c} label="Fix" secondary onPress={props.onFix} />
      )}
    </View>
  );
}

function Button(props: {
  c: Palette;
  label: string;
  onPress: () => unknown;
  secondary?: boolean;
  disabled?: boolean;
}) {
  const { c } = props;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!props.disabled }}
      disabled={props.disabled}
      onPress={() => {
        props.onPress();
      }}
      style={({ pressed }) => [
        styles.button,
        {
          backgroundColor: props.secondary ? c.chip : c.accent,
          opacity: props.disabled ? 0.4 : pressed ? 0.8 : 1,
        },
      ]}
    >
      <Text
        style={[
          styles.buttonText,
          { color: props.secondary ? c.text : c.onAccent },
        ]}
      >
        {props.label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: 16, gap: 16 },
  title: { fontSize: 32, fontWeight: '300' },
  subtitle: { fontSize: 15, marginTop: -8 },
  error: { fontSize: 14 },
  card: { borderRadius: 16, padding: 16, gap: 10 },
  cardTitle: { fontSize: 17, fontWeight: '600' },
  body: { fontSize: 15 },
  intention: { fontSize: 18 },
  hint: { fontSize: 13 },
  log: { fontSize: 12, fontFamily: 'monospace' },
  row: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  permRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  permText: { flex: 1, gap: 2 },
  button: {
    minHeight: 48,
    minWidth: 48,
    paddingHorizontal: 20,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { fontSize: 15, fontWeight: '500' },
});
