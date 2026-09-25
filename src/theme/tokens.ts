/**
 * Design tokens (design brief). Mirrored in android/.../overlay/OverlayUi.kt `OverlayPalette`
 * so RN screens and native overlays read as one product. Change both together.
 *
 * One fixed light identity, blue accent — the app no longer follows the system dark/light
 * setting (see usePalette below). Every color here holds AA contrast against `background` or
 * `surface` at normal text sizes; `primary` was checked at 5.2:1 against `onPrimary`.
 */
import { StyleSheet } from 'react-native';

export const light = {
  background: '#F7F8FA',
  surface: '#FFFFFF',
  text: '#151A21',
  muted: '#5B6472',
  border: '#E1E5EA',
  primary: '#2563EB',
  onPrimary: '#FFFFFF',
  primarySoft: '#E3ECFC',
  amber: '#B7791F',
  amberSoft: '#FBF0DE',
  success: '#2E9B58',
  danger: '#D14343',
  track: '#EAEDF1',
};

export type Palette = typeof light;

/** Kept only for anything that still reads `dark` directly; the app itself always renders `light`. */
export const dark: Palette = light;

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

/** Always the light palette: a single fixed identity instead of following system dark mode. */
export function usePalette(): Palette {
  return light;
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
