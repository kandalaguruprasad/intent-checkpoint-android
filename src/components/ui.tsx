/**
 * Shared primitives built on the design tokens. Every interactive element is ≥48 dp and labelled
 * for TalkBack; text uses sp sizes so it follows the system font scale.
 */
import React from 'react';
import {
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import {
  radius,
  space,
  TOUCH,
  type as t,
  usePalette,
  layout,
} from '../theme/tokens';

export function Card({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
}) {
  const c = usePalette();
  return (
    <View
      style={[
        styles.card,
        { backgroundColor: c.surface, borderColor: c.border },
        style,
      ]}
    >
      {children}
    </View>
  );
}

export function Heading({ children }: { children: React.ReactNode }) {
  const c = usePalette();
  return (
    <Text
      accessibilityRole="header"
      style={[styles.heading, { color: c.text }]}
    >
      {children}
    </Text>
  );
}

export function Muted({
  children,
  size = t.small,
  numberOfLines,
}: {
  children: React.ReactNode;
  size?: number;
  numberOfLines?: number;
}) {
  const c = usePalette();
  return (
    <Text
      numberOfLines={numberOfLines}
      style={{ color: c.muted, fontSize: size }}
    >
      {children}
    </Text>
  );
}

export function Body({
  children,
  numberOfLines,
  weight,
}: {
  children: React.ReactNode;
  numberOfLines?: number;
  weight?: '400' | '500' | '600';
}) {
  const c = usePalette();
  return (
    <Text
      numberOfLines={numberOfLines}
      style={{ color: c.text, fontSize: t.body, fontWeight: weight ?? '400' }}
    >
      {children}
    </Text>
  );
}

type ButtonKind = 'primary' | 'outlined' | 'text';

export function Button({
  label,
  onPress,
  kind = 'primary',
  disabled,
  compact,
  accessibilityHint,
}: {
  label: string;
  onPress: () => unknown;
  kind?: ButtonKind;
  disabled?: boolean;
  compact?: boolean;
  accessibilityHint?: string;
}) {
  const c = usePalette();
  const bg = kind === 'primary' ? c.primary : 'transparent';
  const fg =
    kind === 'primary' ? c.onPrimary : kind === 'text' ? c.primary : c.text;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!disabled }}
      accessibilityHint={accessibilityHint}
      disabled={disabled}
      onPress={() => {
        onPress();
      }}
      style={({ pressed }) => [
        styles.button,
        compact && styles.buttonCompact,
        {
          backgroundColor: bg,
          borderColor: kind === 'outlined' ? c.border : 'transparent',
          opacity: disabled ? 0.45 : pressed ? 0.8 : 1,
        },
      ]}
    >
      <Text style={[styles.buttonText, { color: fg }]}>{label}</Text>
    </Pressable>
  );
}

/** App icon from a data URI, or a lettered tile when the icon isn't available. */
export function AppIcon({
  icon,
  name,
  size = 32,
}: {
  icon: string | null | undefined;
  name: string;
  size?: number;
}) {
  const c = usePalette();
  if (icon) {
    return (
      <Image
        source={{ uri: icon }}
        style={{ width: size, height: size, borderRadius: size / 4 }}
        accessibilityIgnoresInvertColors
        importantForAccessibility="no"
      />
    );
  }
  return (
    <View
      importantForAccessibility="no"
      style={[
        layout.center,
        {
          width: size,
          height: size,
          borderRadius: size / 4,
          backgroundColor: c.primarySoft,
        },
      ]}
    >
      <Text style={[layout.w600, { color: c.primary, fontSize: size * 0.45 }]}>
        {name.slice(0, 1).toUpperCase()}
      </Text>
    </View>
  );
}

export function StatTile({
  label,
  value,
}: {
  label: string;
  value: number | string;
}) {
  const c = usePalette();
  return (
    <View
      accessible
      accessibilityLabel={`${label}: ${value}`}
      style={[
        styles.tile,
        { backgroundColor: c.surface, borderColor: c.border },
      ]}
    >
      <Text style={{ color: c.muted, fontSize: t.tiny }} numberOfLines={2}>
        {label}
      </Text>
      <Text style={[layout.big, { color: c.text, marginTop: space.xs }]}>
        {value}
      </Text>
    </View>
  );
}

/** Horizontal bar; [fraction] is clamped to [0, 1]. */
export function Bar({ fraction, color }: { fraction: number; color: string }) {
  const c = usePalette();
  const f = Math.max(0, Math.min(1, Number.isFinite(fraction) ? fraction : 0));
  return (
    <View style={[styles.track, { backgroundColor: c.track }]}>
      <View
        style={[
          styles.fill,
          {
            backgroundColor: color,
            width: `${Math.max(f * 100, f > 0 ? 3 : 0)}%`,
          },
        ]}
      />
    </View>
  );
}

export type StatusTone = 'ok' | 'paused' | 'attention';

export function StatusPill({
  tone,
  label,
}: {
  tone: StatusTone;
  label: string;
}) {
  const c = usePalette();
  const dot =
    tone === 'ok' ? '#3E9B6A' : tone === 'attention' ? c.amber : c.muted;
  return (
    <View
      accessible
      accessibilityLabel={`Status: ${label}`}
      style={[
        styles.pill,
        { backgroundColor: c.surface, borderColor: c.border },
      ]}
    >
      <View style={[styles.dot, { backgroundColor: dot }]} />
      <Text style={{ color: c.text, fontSize: t.small }}>{label}</Text>
    </View>
  );
}

export function Row({
  children,
  onPress,
  accessibilityLabel,
}: {
  children: React.ReactNode;
  onPress?: () => void;
  accessibilityLabel?: string;
}) {
  if (!onPress) return <View style={styles.row}>{children}</View>;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      onPress={onPress}
      style={({ pressed }) => [styles.row, { opacity: pressed ? 0.7 : 1 }]}
    >
      {children}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: radius.m,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space.m,
  },
  heading: { fontSize: t.heading, fontWeight: '600' },
  button: {
    minHeight: 52,
    minWidth: TOUCH,
    paddingHorizontal: space.l,
    borderRadius: radius.pill,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonCompact: { minHeight: TOUCH },
  buttonText: { fontSize: t.body, fontWeight: '600' },
  tile: {
    flex: 1,
    borderRadius: radius.m,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space.m,
    minHeight: 88,
  },
  track: { height: 10, borderRadius: 5, overflow: 'hidden', flex: 1 },
  fill: { height: 10, borderRadius: 5 },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space.m - 4,
    minHeight: 32,
    gap: space.s,
  },
  dot: { width: 8, height: 8, borderRadius: 4 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: TOUCH + 8,
    gap: space.m - 4,
  },
});
