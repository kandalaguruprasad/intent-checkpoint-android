import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppIcon, Body, Button, Card, Muted, Row } from '../../components/ui';
import type { MonitoredApp } from '../../native/types';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import { space, type as t, usePalette, layout } from '../../theme/tokens';
import { CATEGORY_LABELS } from '../onboarding/appGrouping';

/** Apps tab: what's monitored, and the way back into Choose Apps. Per-app rules come next. */
export function AppsScreen({
  onEdit,
  refreshKey,
}: {
  onEdit: () => void;
  refreshKey: number;
}) {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  const [apps, setApps] = useState<MonitoredApp[] | null>(null);

  const load = useCallback(() => {
    intentCheckpoint.getMonitoredApps().then(setApps, () => setApps([]));
  }, []);
  useEffect(load, [load, refreshKey]);

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
        Apps
      </Text>
      <Muted size={t.body}>Intent asks why before these apps open.</Muted>
      <Card>
        {apps?.length === 0 && <Muted size={t.body}>No apps yet.</Muted>}
        {apps?.map(a => (
          <Row key={a.packageName}>
            <AppIcon icon={a.icon} name={a.appName} size={36} />
            <View style={layout.flex1}>
              <Body>{a.appName}</Body>
              <Muted>{CATEGORY_LABELS[a.category]}</Muted>
            </View>
          </Row>
        ))}
      </Card>
      <Button label="Edit apps" onPress={onEdit} />
      <Muted>Removing an app keeps its history.</Muted>
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
});
