import React from 'react';
import { ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { PermissionRow } from '../../components/PermissionRow';
import { Body, Button, Card, Heading, Muted, Row } from '../../components/ui';
import type { OverlayPreviewKind } from '../../native/types';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import { space, type as t, usePalette, layout } from '../../theme/tokens';
import { usePreview } from '../shell/preview';
import { formatMs, latencyVerdict } from './format';
import { useDiagnostics } from './useDiagnostics';

const PREVIEWS: { kind: OverlayPreviewKind; label: string }[] = [
  { kind: 'pause', label: 'Checkpoint' },
  { kind: 'reminder', label: 'Reminder' },
  { kind: 'reminder-warning', label: 'Reminder, 2 min warning' },
  { kind: 'reminder-no-timer', label: 'Reminder, no timer' },
  { kind: 'timesup', label: "Time's up" },
  { kind: 'complete', label: 'Session complete' },
];

/** Settings: monitoring, permission health, design previews and diagnostics. */
export function SettingsScreen({
  onPreviewChooseApps,
  onRerunSetup,
}: {
  onPreviewChooseApps: () => void;
  onRerunSetup: () => void;
}) {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  const d = useDiagnostics();
  const { fixtures, setFixtures } = usePreview();
  const p = d.permissions;
  const verdict = latencyVerdict(
    d.latency?.medianMs ?? null,
    d.latency?.p95Ms ?? null,
  );

  return (
    <ScrollView
      style={{ backgroundColor: c.background }}
      contentContainerStyle={[
        styles.content,
        { paddingTop: insets.top + space.m },
      ]}
    >
      <Text
        accessibilityRole="header"
        style={[styles.title, { color: c.text }]}
      >
        Settings
      </Text>
      {d.error && <Text style={{ color: c.amber }}>{d.error}</Text>}

      <Card>
        <Row>
          <View style={layout.flex1}>
            <Body weight="500">Monitoring</Body>
            <Muted>
              {d.status?.running
                ? 'On — Intent asks why before your apps open.'
                : 'Off — no checkpoints are shown.'}
            </Muted>
          </View>
          <Switch
            accessibilityLabel="Monitoring"
            value={!!d.status?.enabled}
            onValueChange={d.toggleMonitoring}
            trackColor={{ true: c.primary, false: c.border }}
            thumbColor={c.surface}
          />
        </Row>
      </Card>

      <Card>
        <Heading>Permission health</Heading>
        <PermissionRow
          label="Usage Access"
          why="Notices when a chosen app opens."
          ok={p?.usageAccess}
          onFix={intentCheckpoint.openUsageAccessSettings}
        />
        <PermissionRow
          label="Display over other apps"
          why="Shows the checkpoint and reminder."
          ok={p?.overlay}
          onFix={intentCheckpoint.openOverlaySettings}
        />
        <PermissionRow
          label="Notifications"
          why="Keeps the 'Intentional use is active' notice visible."
          ok={p?.notifications}
          onFix={d.requestNotifications}
        />
        <PermissionRow
          label="Exact alarms"
          why="Ends the timer on time even when the phone is idle."
          ok={p?.exactAlarms}
          optional
          onFix={intentCheckpoint.openExactAlarmSettings}
        />
        <PermissionRow
          label="Background reliability"
          why="Some phones stop background apps. Excluding Intent helps."
          ok={p?.batteryUnrestricted}
          optional
          okLabel="Unrestricted"
          badLabel="Optimization detected"
          onFix={intentCheckpoint.openBatteryOptimizationSettings}
        />
      </Card>

      <Card>
        <Heading>Preview</Heading>
        <View style={{ marginVertical: space.s }}>
          <Muted>
            Shows the real overlays with sample data. Nothing is saved; tap
            through or press back to close.
          </Muted>
        </View>
        <View style={styles.previewGrid}>
          {PREVIEWS.map(pv => (
            <View key={pv.kind} style={styles.previewCell}>
              <Button
                label={pv.label}
                kind="outlined"
                compact
                disabled={!p?.overlay}
                onPress={() =>
                  intentCheckpoint.previewOverlay(pv.kind).catch(() => {})
                }
              />
            </View>
          ))}
        </View>
        <Row>
          <View style={layout.flex1}>
            <Body>Preview screens with sample data</Body>
            <Muted>
              Today and Choose Apps show fixtures instead of your data.
            </Muted>
          </View>
          <Switch
            accessibilityLabel="Preview screens with sample data"
            value={fixtures}
            onValueChange={setFixtures}
            trackColor={{ true: c.primary, false: c.border }}
            thumbColor={c.surface}
          />
        </Row>
        <Button
          label="Preview Choose Apps"
          kind="text"
          compact
          onPress={onPreviewChooseApps}
        />
      </Card>

      <Card>
        <Heading>Diagnostics</Heading>
        <View style={{ marginTop: space.s, gap: space.xs }}>
          <Body>
            Detection latency: {d.latency?.count ?? 0} samples · median{' '}
            {formatMs(d.latency?.medianMs ?? null)} · p95{' '}
            {formatMs(d.latency?.p95Ms ?? null)}
          </Body>
          <Muted>
            {verdict === 'insufficient'
              ? 'Open a monitored app a few times to collect samples.'
              : verdict === 'go'
              ? 'Within target (median < 2 s, p95 < 4 s).'
              : 'Outside target (median < 2 s, p95 < 4 s).'}
          </Muted>
        </View>
        <View style={styles.row2}>
          <Button label="Refresh" kind="outlined" compact onPress={d.refresh} />
          <Button
            label="Clear samples"
            kind="outlined"
            compact
            onPress={d.clearLatency}
          />
        </View>
        {d.log.slice(0, 12).map((e, i) => (
          <Text key={`${e.at}-${i}`} style={[styles.log, { color: c.muted }]}>
            {new Date(e.at).toLocaleTimeString()} {e.text}
          </Text>
        ))}
      </Card>

      <Button label="Run setup again" kind="text" onPress={onRerunSetup} />
      <Muted size={t.tiny}>
        Everything stays on this phone. No account, no servers, no tracking.
      </Muted>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: space.l,
    paddingBottom: space.xl,
    gap: space.m,
  },
  title: { fontSize: 26, fontWeight: '600' },
  previewGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.s,
    marginBottom: space.s,
  },
  previewCell: { flexGrow: 1, flexBasis: '45%' },
  row2: {
    flexDirection: 'row',
    gap: space.s,
    marginVertical: space.s,
    flexWrap: 'wrap',
  },
  log: { fontSize: 12, fontFamily: 'monospace' },
});
