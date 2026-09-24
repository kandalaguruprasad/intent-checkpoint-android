import React, { useCallback, useEffect, useState } from 'react';
import {
  RefreshControl,
  SectionList,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppIcon, Muted } from '../../components/ui';
import type { MonitoredApp, SessionRecord } from '../../native/types';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import {
  radius,
  space,
  type as t,
  usePalette,
  layout,
} from '../../theme/tokens';
import {
  dayLabel,
  formatMinutes,
  formatTimeOfDay,
  localDayBounds,
} from '../../utils/time';
import { useEngineEvents } from '../shell/useEngineData';

export function sessionTag(s: SessionRecord): string {
  if (s.startedAt == null) return 'Not now';
  switch (s.state) {
    case 'SESSION_COMPLETED':
      return s.completionReason === 'done' ? 'Done' : 'Left';
    case 'SESSION_ABANDONED':
      return s.completionReason === 'device_restarted'
        ? 'Phone restarted'
        : 'Ended';
    default:
      return 'In progress';
  }
}

/** Minimal History tab: the last 7 days of sessions, read from SQLite. Detail screens come later. */
export function HistoryScreen() {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  const [sections, setSections] = useState<
    { title: string; data: SessionRecord[] }[]
  >([]);
  const [icons, setIcons] = useState<Map<string, string | null>>(new Map());
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    const now = Date.now();
    const from = localDayBounds(now, 6).start;
    const to = localDayBounds(now).end;
    try {
      const [list, monitored] = await Promise.all([
        intentCheckpoint.listSessions(from, to, 300),
        intentCheckpoint.getMonitoredApps().catch(() => [] as MonitoredApp[]),
      ]);
      setIcons(new Map(monitored.map(m => [m.packageName, m.icon])));
      const byDay = new Map<number, SessionRecord[]>();
      for (const s of list) {
        const day = localDayBounds(s.createdAt).start;
        byDay.set(day, [...(byDay.get(day) ?? []), s]);
      }
      setSections(
        [...byDay.entries()].map(([day, data]) => ({
          title: dayLabel(day, now),
          data,
        })),
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);
  useEngineEvents(e => {
    if (e.type === 'sessionStateChanged') load();
  });

  return (
    <SectionList
      style={{ backgroundColor: c.background }}
      contentContainerStyle={{
        paddingTop: insets.top + space.m,
        paddingHorizontal: space.l,
        paddingBottom: space.xl,
      }}
      sections={sections}
      keyExtractor={s => s.id}
      refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}
      ListHeaderComponent={
        <Text
          accessibilityRole="header"
          style={[styles.title, { color: c.text }]}
        >
          History
        </Text>
      }
      ListEmptyComponent={
        loading ? undefined : (
          <Muted size={t.body}>Your sessions will show up here.</Muted>
        )
      }
      renderSectionHeader={({ section }) => (
        <Text
          accessibilityRole="header"
          style={[styles.day, { color: c.muted }]}
        >
          {section.title}
        </Text>
      )}
      renderItem={({ item }) => {
        const tag = sessionTag(item);
        const planned = item.plannedDurationSeconds;
        const detail =
          item.startedAt == null
            ? ''
            : planned != null
            ? `${formatMinutes(item.foregroundSeconds)} of ${formatMinutes(
                planned,
              )}`
            : `${formatMinutes(item.foregroundSeconds)} · no timer`;
        return (
          <View
            accessible
            accessibilityLabel={`${item.appName}, ${
              item.startedAt ? item.intention : 'chose not to open'
            }, ${formatTimeOfDay(item.createdAt)}, ${tag}. ${detail}`}
            style={[
              styles.item,
              { backgroundColor: c.surface, borderColor: c.border },
            ]}
          >
            <AppIcon icon={icons.get(item.packageName)} name={item.appName} />
            <View style={layout.flex1}>
              <Text
                style={{ color: c.text, fontSize: t.body }}
                numberOfLines={1}
              >
                {item.startedAt
                  ? item.intention
                  : `Chose not to open ${item.appName}`}
              </Text>
              <Muted>
                {formatTimeOfDay(item.createdAt)}
                {detail ? ` · ${detail}` : ''}
                {item.extensionCount > 0
                  ? ` · extended ×${item.extensionCount}`
                  : ''}
              </Muted>
            </View>
            <View style={[styles.tag, { backgroundColor: c.primarySoft }]}>
              <Text style={{ color: c.text, fontSize: t.tiny }}>{tag}</Text>
            </View>
          </View>
        );
      }}
    />
  );
}

const styles = StyleSheet.create({
  title: { fontSize: 26, fontWeight: '600', marginBottom: space.s },
  day: {
    fontSize: t.small,
    fontWeight: '600',
    marginTop: space.m,
    marginBottom: space.s,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m - 4,
    padding: space.m - 4,
    borderRadius: radius.m,
    borderWidth: StyleSheet.hairlineWidth,
    marginBottom: space.s,
    minHeight: 64,
  },
  tag: {
    borderRadius: radius.pill,
    paddingHorizontal: space.s + 2,
    paddingVertical: 4,
  },
});
