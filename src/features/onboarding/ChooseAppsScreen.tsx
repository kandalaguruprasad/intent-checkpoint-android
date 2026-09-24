import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  BackHandler,
  Pressable,
  SectionList,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppIcon, Button, Muted } from '../../components/ui';
import {
  fixtureLaunchable,
  fixtureMonitored,
} from '../../fixtures/designFixtures';
import type { LaunchableApp } from '../../native/types';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import {
  radius,
  space,
  TOUCH,
  type as t,
  usePalette,
  layout,
} from '../../theme/tokens';
import { usePreview } from '../shell/preview';
import { CATEGORY_LABELS, groupApps } from './appGrouping';

/**
 * Choose Apps (design screen 6). Installed, launchable apps grouped by category, searchable, with
 * sensitive apps pulled into a "Not recommended" group. Used in onboarding and from Apps → Edit.
 */
export function ChooseAppsScreen({
  mode,
  step,
  onDone,
  onBack,
}: {
  mode: 'onboarding' | 'edit';
  /** Onboarding progress, e.g. { index: 0, count: 2 }. */
  step?: { index: number; count: number };
  onDone: () => void;
  onBack?: () => void;
}) {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  const { fixtures } = usePreview();
  const [apps, setApps] = useState<LaunchableApp[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      if (fixtures) {
        setApps(fixtureLaunchable);
        setSelected(
          new Set(fixtureMonitored.slice(0, 2).map(a => a.packageName)),
        );
        return;
      }
      const [all, monitored] = await Promise.all([
        intentCheckpoint.listLaunchableApps(),
        intentCheckpoint.getMonitoredApps(),
      ]);
      setApps(all);
      setSelected(new Set(monitored.map(m => m.packageName)));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [fixtures]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!onBack) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      onBack();
      return true;
    });
    return () => sub.remove();
  }, [onBack]);

  const sections = useMemo(() => groupApps(apps ?? [], query), [apps, query]);

  const toggle = (app: LaunchableApp) => {
    const isOn = selected.has(app.packageName);
    const apply = () =>
      setSelected(prev => {
        const next = new Set(prev);
        if (isOn) next.delete(app.packageName);
        else next.add(app.packageName);
        return next;
      });
    if (!isOn && app.sensitive) {
      Alert.alert(
        `Add ${app.label}?`,
        'We recommend not adding banking, payment, authenticator or password apps. A pause in front of a payment or a login code only gets in the way.',
        [
          { text: 'Keep it off', style: 'cancel' },
          { text: 'Add anyway', onPress: apply },
        ],
      );
      return;
    }
    apply();
  };

  const save = async () => {
    if (fixtures) {
      onDone();
      return;
    }
    setSaving(true);
    try {
      const byPkg = new Map((apps ?? []).map(a => [a.packageName, a]));
      await intentCheckpoint.setMonitoredApps(
        [...selected]
          .map(p => byPkg.get(p))
          .filter((a): a is LaunchableApp => !!a)
          .map(a => ({
            packageName: a.packageName,
            appName: a.label,
            category: a.category,
          })),
      );
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const count = selected.size;

  return (
    <View
      style={[
        styles.screen,
        { backgroundColor: c.background, paddingTop: insets.top },
      ]}
    >
      <View style={styles.topBar}>
        {onBack ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Back"
            onPress={onBack}
            style={styles.back}
          >
            <Text style={[{ color: c.text }, layout.back]}>‹</Text>
          </Pressable>
        ) : (
          <View style={styles.back} />
        )}
        {step && (
          <View
            style={styles.dots}
            accessible
            accessibilityLabel={`Step ${step.index + 1} of ${step.count}`}
          >
            {Array.from({ length: step.count }, (_, i) => (
              <View
                key={i}
                style={[
                  styles.dot,
                  i === step.index ? styles.dotActive : null,
                  { backgroundColor: i === step.index ? c.primary : c.border },
                ]}
              />
            ))}
          </View>
        )}
        <View style={styles.back} />
      </View>

      <SectionList
        sections={sections}
        keyExtractor={a => a.packageName}
        keyboardShouldPersistTaps="handled"
        stickySectionHeadersEnabled={false}
        contentContainerStyle={{
          paddingHorizontal: space.l,
          paddingBottom: space.xl,
        }}
        ListHeaderComponent={
          <View style={{ gap: space.s, marginBottom: space.m }}>
            <Text
              accessibilityRole="header"
              style={[styles.title, { color: c.text }]}
            >
              Which apps pull you in?
            </Text>
            <Muted size={t.body}>
              Start with one or two apps. You can change this anytime in Apps.
            </Muted>
            <View
              style={[
                styles.search,
                { backgroundColor: c.surface, borderColor: c.border },
              ]}
            >
              <Text style={[{ color: c.muted }, layout.search]}>⌕</Text>
              <TextInput
                value={query}
                onChangeText={setQuery}
                placeholder="Search apps…"
                placeholderTextColor={c.muted}
                accessibilityLabel="Search apps"
                style={[styles.searchInput, { color: c.text }]}
                autoCorrect={false}
              />
            </View>
            {error && (
              <View style={{ gap: space.s }}>
                <Text style={{ color: c.amber }}>
                  Couldn't load your apps: {error}
                </Text>
                <Button
                  label="Try again"
                  kind="outlined"
                  compact
                  onPress={load}
                />
              </View>
            )}
          </View>
        }
        ListEmptyComponent={
          apps === null && !error ? (
            <ActivityIndicator
              color={c.primary}
              style={{ marginTop: space.xl }}
            />
          ) : apps && apps.length > 0 ? (
            <Muted size={t.body}>No apps match “{query}”.</Muted>
          ) : undefined
        }
        renderSectionHeader={({ section }) => (
          <View
            style={{ marginTop: space.m, marginBottom: space.s, gap: space.xs }}
          >
            <Text
              accessibilityRole="header"
              style={[styles.sectionTitle, { color: c.text }]}
            >
              {section.title}
            </Text>
            {section.note && <Muted>{section.note}</Muted>}
          </View>
        )}
        renderItem={({ item, index, section }) => {
          const on = selected.has(item.packageName);
          const first = index === 0;
          const last = index === section.data.length - 1;
          return (
            <Pressable
              accessibilityRole="switch"
              accessibilityState={{ checked: on }}
              accessibilityLabel={`${item.label}, ${
                CATEGORY_LABELS[item.category]
              }${item.sensitive ? ', not recommended' : ''}`}
              onPress={() => toggle(item)}
              style={({ pressed }) => [
                styles.item,
                {
                  backgroundColor: on ? c.primarySoft : c.surface,
                  borderColor: c.border,
                  borderTopLeftRadius: first ? radius.m : 0,
                  borderTopRightRadius: first ? radius.m : 0,
                  borderBottomLeftRadius: last ? radius.m : 0,
                  borderBottomRightRadius: last ? radius.m : 0,
                  borderTopWidth: first ? StyleSheet.hairlineWidth : 0,
                  opacity: pressed ? 0.8 : 1,
                },
              ]}
            >
              <AppIcon icon={item.icon} name={item.label} size={36} />
              <View style={layout.flex1}>
                <Text
                  style={[{ color: c.text, fontSize: t.body }, layout.w500]}
                  numberOfLines={1}
                >
                  {item.label}
                </Text>
                <Muted numberOfLines={1}>
                  {item.sensitive
                    ? 'Not recommended'
                    : CATEGORY_LABELS[item.category]}
                </Muted>
              </View>
              <Switch
                value={on}
                onValueChange={() => toggle(item)}
                trackColor={{ true: c.primary, false: c.border }}
                thumbColor={c.surface}
                importantForAccessibility="no-hide-descendants"
              />
            </Pressable>
          );
        }}
      />

      <View
        style={[
          styles.footer,
          {
            backgroundColor: c.background,
            borderColor: c.border,
            paddingBottom: insets.bottom + space.m,
          },
        ]}
      >
        <Text
          style={[layout.flex1, { color: c.text, fontSize: t.body }]}
          accessibilityLiveRegion="polite"
        >
          {count === 0
            ? 'No apps selected'
            : `${count} ${count === 1 ? 'app' : 'apps'} selected`}
        </Text>
        <View style={layout.minW140}>
          <Button
            label={saving ? 'Saving…' : mode === 'edit' ? 'Save' : 'Continue'}
            disabled={count === 0 || saving || apps === null}
            onPress={save}
          />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: space.s,
    minHeight: TOUCH,
  },
  back: {
    width: TOUCH,
    height: TOUCH,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dots: { flexDirection: 'row', gap: 6 },
  dot: { height: 6, borderRadius: 3, width: 12 },
  dotActive: { width: 24 },
  title: { fontSize: 26, fontWeight: '600' },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space.m,
    minHeight: TOUCH,
    gap: space.s,
    marginTop: space.s,
  },
  searchInput: { flex: 1, fontSize: t.body, paddingVertical: space.s },
  sectionTitle: { fontSize: t.body, fontWeight: '600' },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m - 4,
    paddingHorizontal: space.m - 4,
    minHeight: 64,
    borderLeftWidth: StyleSheet.hairlineWidth,
    borderRightWidth: StyleSheet.hairlineWidth,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  footer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m,
    paddingHorizontal: space.l,
    paddingTop: space.m - 4,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
});
