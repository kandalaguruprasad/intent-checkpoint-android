import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  space,
  TOUCH,
  type as t,
  usePalette,
  layout,
} from '../../theme/tokens';

export type Tab = 'today' | 'history' | 'apps' | 'settings';

const TABS: { key: Tab; label: string; glyph: string }[] = [
  { key: 'today', label: 'Today', glyph: '◔' },
  { key: 'history', label: 'History', glyph: '◷' },
  { key: 'apps', label: 'Apps', glyph: '▦' },
  { key: 'settings', label: 'Settings', glyph: '⚙' },
];

/** Bottom tabs. Plain RN (no navigation library) keeps the native surface small for now. */
export function TabBar({
  tab,
  onChange,
}: {
  tab: Tab;
  onChange: (t: Tab) => void;
}) {
  const c = usePalette();
  const insets = useSafeAreaInsets();
  return (
    <View
      accessibilityRole="tablist"
      style={[
        styles.bar,
        {
          backgroundColor: c.surface,
          borderColor: c.border,
          paddingBottom: insets.bottom,
        },
      ]}
    >
      {TABS.map(item => {
        const active = item.key === tab;
        return (
          <Pressable
            key={item.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            accessibilityLabel={item.label}
            onPress={() => onChange(item.key)}
            style={styles.tab}
          >
            <Text
              style={[{ color: active ? c.primary : c.muted }, layout.glyph]}
            >
              {item.glyph}
            </Text>
            <Text
              style={[
                active ? layout.w600 : layout.w400,
                { color: active ? c.primary : c.muted, fontSize: t.tiny },
              ]}
            >
              {item.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth },
  tab: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: TOUCH + 8,
    paddingTop: space.xs,
  },
});
