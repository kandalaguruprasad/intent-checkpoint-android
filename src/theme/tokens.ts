/**
 * Design tokens (design brief). Mirrored in android/.../overlay/OverlayUi.kt `OverlayPalette`
 * so RN screens and native overlays read as one product. Change both together.
 */
import { StyleSheet, useColorScheme } from 'react-native';

export const light = {
  background: '#F7F7F4',
  surface: '#FFFFFF',
  text: '#1B2420',
  muted: '#5E6B64',
  border: '#DDE3DD',
  primary: '#507764',
  onPrimary: '#FFFFFF',
  primarySoft: '#E4ECE7',
  amber: '#B67932',
  amberSoft: '#F6EBDD',
  track: '#E8ECE8',
};

export type Palette = typeof light;

export const dark: Palette = {
  background: '#151B19',
  surface: '#1F2724',
  text: '#F2F5F3',
  muted: '#A7B3AD',
  border: '#34403A',
  primary: '#8DB09C',
  onPrimary: '#10201A',
  primarySoft: '#26352E',
  amber: '#E0A560',
  amberSoft: '#3A2D1D',
  track: '#2B3531',
};

/** 8 dp grid. */
export const space = { xs: 4, s: 8, m: 16, l: 20, xl: 24, xxl: 32 } as const;

export const radius = { s: 10, m: 14, l: 20, pill: 999 } as const;

/** Sizes in sp; RN Text scales with the system font size by default. */
export const type = {
  display: 30,
  title: 22,
  heading: 17,
  body: 15,
  small: 13,
  tiny: 12,
} as const;

/** Minimum touch target (dp). */
export const TOUCH = 48;

export function usePalette(): Palette {
  return useColorScheme() === 'dark' ? dark : light;
}

/** Small shared layout/type helpers so screens don't need inline literal styles. */
export const layout = StyleSheet.create({
  flex1: { flex: 1 },
  flex1Gap2: { flex: 1, gap: 2 },
  center: { alignItems: 'center', justifyContent: 'center' },
  flex1Center: { flex: 1, justifyContent: 'center' },
  w400: { fontWeight: '400' },
  w500: { fontWeight: '500' },
  w600: { fontWeight: '600' },
  big: { fontSize: 26, fontWeight: '600' },
  glyph: { fontSize: 18 },
  back: { fontSize: 22 },
  search: { fontSize: 16 },
  minW140: { minWidth: 140 },
});
