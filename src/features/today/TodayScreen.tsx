import React, { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  AppIcon,
  Bar,
  Body,
  Button,
  Card,
  Heading,
  Muted,
  Row,
  StatTile,
  StatusPill,
} from '../../components/ui';
import type { SessionRecord } from '../../native/types';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import { space, type as t, usePalette, layout } from '../../theme/tokens';
import { formatClock, formatMinutes } from '../../utils/time';
import { usePreview } from '../shell/preview';
import { useToday } from '../shell/useEngineData';
import {
  isLiveSession,
  monitoringStatus,
  sessionStateLabel,
} from './todayModel';

/**
 * Today (design screen 5). Every number comes from persisted sessions via the engine; the only
 * sample data is the explicit design-preview mode.
 */
export function TodayScreen({
  onManageApps,
  onOpenSettings,
}: {
  onManageApps: () => void;
  onOpenSettings: () => void;
}) {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  const { fixtures } = usePreview();
  const d = useToday();
  const status = monitoringStatus(d.permissions, d.status);
  const permissionProblem =
    d.permissions && (!d.permissions.usageAccess || !d.permissions.overlay);
  const s = d.summary;
  const live = isLiveSession(d.session) ? d.session : null;
  const opensByPkg = new Map(s?.perApp.map(a => [a.packageName, a]) ?? []);

  return (
    <ScrollView
      style={{ backgroundColor: c.background }}
      contentContainerStyle={[
        styles.content,
        { paddingTop: insets.top + space.m },
      ]}
    >
      <View style={styles.header}>
        <Text
          accessibilityRole="header"
          style={[styles.brand, { color: c.text }]}
        >
          Intent
        </Text>
        <StatusPill tone={status.tone} label={status.label} />
      </View>
      {fixtures && <Muted>Showing sample data (Settings → Preview).</Muted>}

      {permissionProblem && (
        <Card style={{ borderColor: c.amber, backgroundColor: c.amberSoft }}>
          <Heading>Intent can't pause apps right now</Heading>
          <View style={{ marginTop: space.xs, marginBottom: space.m }}>
            <Muted size={t.body}>
              {!d.permissions!.usageAccess
                ? 'Usage Access is off, so Intent can’t notice when your apps open.'
                : 'Display over other apps is off, so Intent can’t show the checkpoint.'}
            </Muted>
          </View>
          <Button
            label="Fix in Settings"
            compact
            onPress={
              !d.permissions!.usageAccess
                ? intentCheckpoint.openUsageAccessSettings
                : intentCheckpoint.openOverlaySettings
            }
          />
        </Card>
      )}

      {d.error && (
        <Card>
          <Heading>Couldn't load today</Heading>
          <View style={{ marginVertical: space.s }}>
            <Muted size={t.body}>{d.error}</Muted>
          </View>
          <Button
            label="Try again"
            kind="outlined"
            compact
            onPress={d.refresh}
          />
        </Card>
      )}

      {live && (
        <ActiveSessionCard
          session={live}
          icon={
            d.monitored.find(m => m.packageName === live.packageName)?.icon ??
            null
          }
        />
      )}

      <Text
        accessibilityRole="header"
        style={[styles.section, { color: c.text }]}
      >
        Today
      </Text>

      {s && s.opensNoticed === 0 && !d.loading ? (
        <Card>
          <Heading>Nothing yet today</Heading>
          <View style={{ marginTop: space.xs }}>
            <Muted size={t.body}>
              {d.monitored.length === 0
                ? 'Choose the apps you want to be more intentional about, and Intent will ask why before they open.'
                : `Open ${d.monitored[0].appName} and Intent will ask why first. Your day builds up here.`}
            </Muted>
          </View>
          {d.monitored.length === 0 && (
            <View style={{ marginTop: space.m }}>
              <Button label="Choose apps" compact onPress={onManageApps} />
            </View>
          )}
        </Card>
      ) : (
        <>
          <View style={styles.tiles}>
            <StatTile label="Opens noticed" value={s?.opensNoticed ?? '–'} />
            <StatTile
              label="Intentional sessions"
              value={s?.intentionalSessions ?? '–'}
            />
            <StatTile
              label="Chose not to open"
              value={s?.choseNotToOpen ?? '–'}
            />
          </View>
          <PlannedVsActual
            planned={s?.plannedSeconds ?? 0}
            actual={s?.actualSecondsTimed ?? 0}
          />
        </>
      )}

      <Card>
        <View style={styles.cardHeader}>
          <Heading>Monitored apps</Heading>
          <Button label="Manage" kind="text" compact onPress={onManageApps} />
        </View>
        {d.monitored.length === 0 && !d.loading && (
          <Muted size={t.body}>No apps yet.</Muted>
        )}
        {d.monitored.map(app => {
          const day = opensByPkg.get(app.packageName);
          const opens = day?.opens ?? 0;
          return (
            <Row
              key={app.packageName}
              accessibilityLabel={`${app.appName}, ${opens} opens today`}
              onPress={onManageApps}
            >
              <AppIcon icon={app.icon} name={app.appName} />
              <View style={layout.flex1}>
                <Body>{app.appName}</Body>
              </View>
              <Muted>
                {opens} {opens === 1 ? 'open' : 'opens'}
                {day && day.actualSeconds >= 60
                  ? ` · ${formatMinutes(day.actualSeconds)}`
                  : ''}
              </Muted>
            </Row>
          );
        })}
      </Card>

      {!d.status?.enabled &&
        !permissionProblem &&
        d.monitored.length > 0 &&
        !d.loading && (
          <Card>
            <Heading>Monitoring is paused</Heading>
            <View style={{ marginVertical: space.s }}>
              <Muted size={t.body}>
                Turn it back on to get a checkpoint before your apps open.
              </Muted>
            </View>
            <Button
              label="Open Settings"
              kind="outlined"
              compact
              onPress={onOpenSettings}
            />
          </Card>
        )}
    </ScrollView>
  );
}

function PlannedVsActual({
  planned,
  actual,
}: {
  planned: number;
  actual: number;
}) {
  const c = usePalette();
  const scale = Math.max(planned, actual, 1);
  return (
    <Card>
      <Heading>Planned vs actual time</Heading>
      {planned === 0 ? (
        <View style={{ marginTop: space.s }}>
          <Muted size={t.body}>No timed sessions yet today.</Muted>
        </View>
      ) : (
        <>
          <View
            style={styles.barRow}
            accessible
            accessibilityLabel={`Planned ${formatMinutes(planned)}`}
          >
            <Text style={[styles.barLabel, { color: c.muted }]}>Planned</Text>
            <Bar fraction={planned / scale} color={c.muted} />
            <Text style={[styles.barValue, { color: c.text }]}>
              {formatMinutes(planned)}
            </Text>
          </View>
          <View
            style={styles.barRow}
            accessible
            accessibilityLabel={`Actual ${formatMinutes(actual)}`}
          >
            <Text style={[styles.barLabel, { color: c.muted }]}>Actual</Text>
            <Bar fraction={actual / scale} color={c.primary} />
            <Text style={[styles.barValue, { color: c.text }]}>
              {formatMinutes(actual)}
            </Text>
          </View>
          <Muted size={t.tiny}>
            Timed sessions only. Actual is time the app was on screen.
          </Muted>
        </>
      )}
    </Card>
  );
}

function ActiveSessionCard({
  session,
  icon,
}: {
  session: SessionRecord;
  icon: string | null;
}) {
  const c = usePalette();
  const { fixtures } = usePreview();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const time =
    session.plannedEndAt != null
      ? `${formatClock(session.plannedEndAt - now)} left`
      : `${formatMinutes((now - (session.startedAt ?? now)) / 1000)} in`;
  return (
    <Card style={{ borderColor: c.primary }}>
      <Row>
        <AppIcon icon={icon} name={session.appName} size={40} />
        <View style={layout.flex1}>
          <Muted>
            {session.appName} · {sessionStateLabel(session)}
          </Muted>
          <Text
            style={[{ color: c.text, fontSize: t.heading }, layout.w600]}
            numberOfLines={2}
          >
            {session.intention}
          </Text>
        </View>
        <Text
          style={{
            color: c.text,
            fontSize: t.body,
            fontVariant: ['tabular-nums'],
          }}
        >
          {time}
        </Text>
      </Row>
      <View style={{ marginTop: space.s }}>
        <Button
          label="End session"
          kind="outlined"
          compact
          disabled={fixtures}
          onPress={() =>
            intentCheckpoint.completeSession(session.id, 'left').catch(() => {})
          }
        />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: space.l,
    paddingBottom: space.xxl,
    gap: space.m,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: { fontSize: t.display, fontWeight: '600' },
  section: { fontSize: t.heading, fontWeight: '600', marginTop: space.s },
  tiles: { flexDirection: 'row', gap: space.s },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  barRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m - 4,
    marginTop: space.m - 4,
  },
  barLabel: { width: 64, fontSize: t.small },
  barValue: { width: 72, textAlign: 'right', fontSize: t.small },
});
