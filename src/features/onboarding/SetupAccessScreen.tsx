import React, { useCallback, useEffect, useState } from 'react';
import {
  PermissionsAndroid,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { PermissionRow } from '../../components/PermissionRow';
import { Button, Card, Muted } from '../../components/ui';
import { useAppActive } from '../../hooks/useAppActive';
import type { PermissionState } from '../../native/types';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import { space, type as t, usePalette, layout } from '../../theme/tokens';

/**
 * Onboarding step 2: the special accesses, each with a one-line reason, right before the
 * Settings deep link (PRD §36.1). Refreshes when the user comes back from Settings.
 */
export function SetupAccessScreen({ onDone }: { onDone: () => void }) {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  const [p, setP] = useState<PermissionState | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    intentCheckpoint
      .getPermissionState()
      .then(setP, e => setError(String(e?.message ?? e)));
  }, []);
  useEffect(refresh, [refresh]);
  useAppActive(refresh);

  const ready = !!p?.usageAccess && !!p?.overlay;

  const finish = async () => {
    setError(null);
    try {
      if (
        Platform.OS === 'android' &&
        Platform.Version >= 33 &&
        !p?.notifications
      ) {
        await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
        );
      }
      await intentCheckpoint.startMonitoring();
      await intentCheckpoint.setOnboardingComplete(true);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <View style={[layout.flex1, { backgroundColor: c.background }]}>
      <ScrollView
        contentContainerStyle={[
          styles.content,
          { paddingTop: insets.top + space.xl },
        ]}
      >
        <Text
          accessibilityRole="header"
          style={[styles.title, { color: c.text }]}
        >
          Two permissions, then you're set
        </Text>
        <Muted size={t.body}>
          Android asks you to turn these on in Settings. Intent never sees what
          you do inside apps.
        </Muted>
        <Card>
          <PermissionRow
            label="Usage Access"
            why="Lets Intent notice when one of your chosen apps opens."
            ok={p?.usageAccess}
            onFix={intentCheckpoint.openUsageAccessSettings}
          />
          <PermissionRow
            label="Display over other apps"
            why="Shows the checkpoint and the small reminder on top of the app."
            ok={p?.overlay}
            onFix={intentCheckpoint.openOverlaySettings}
          />
          <PermissionRow
            label="Background reliability"
            why="Some phones stop background apps to save battery. Allow Intent to keep running."
            ok={p?.batteryUnrestricted}
            optional
            okLabel="Unrestricted"
            badLabel="Battery optimization is on"
            onFix={intentCheckpoint.openBatteryOptimizationSettings}
          />
        </Card>
        <Muted>
          Notifications: Android requires a visible notification while Intent
          runs. You'll be asked next.
        </Muted>
        {error && <Text style={{ color: c.amber }}>{error}</Text>}
      </ScrollView>
      <View
        style={[
          styles.footer,
          { paddingBottom: insets.bottom + space.m, borderColor: c.border },
        ]}
      >
        <Button
          label="Start using Intent"
          disabled={!ready}
          accessibilityHint={
            ready
              ? undefined
              : 'Turn on Usage Access and Display over other apps first'
          }
          onPress={finish}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: space.l,
    paddingBottom: space.xl,
    gap: space.m,
  },
  title: { fontSize: 26, fontWeight: '600' },
  footer: {
    paddingHorizontal: space.l,
    paddingTop: space.m - 4,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
});
