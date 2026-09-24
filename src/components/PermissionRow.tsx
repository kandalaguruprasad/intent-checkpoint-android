import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { space, type as t, usePalette, layout } from '../theme/tokens';
import { Button } from './ui';

/** One row of permission health: what it does, current state, one-tap fix. Calm copy (PRD §36.2). */
export function PermissionRow(props: {
  label: string;
  why: string;
  ok: boolean | undefined;
  optional?: boolean;
  okLabel?: string;
  badLabel?: string;
  onFix: () => unknown;
}) {
  const c = usePalette();
  const { ok } = props;
  const state =
    ok === undefined
      ? 'Checking…'
      : ok
      ? props.okLabel ?? 'On'
      : props.badLabel ?? 'Off';
  return (
    <View style={styles.row} accessible={false}>
      <View style={layout.flex1Gap2}>
        <Text style={[{ color: c.text, fontSize: t.body }, layout.w500]}>
          {props.label}
          {props.optional ? ' (recommended)' : ''}
        </Text>
        <Text style={{ color: c.muted, fontSize: t.small }}>{props.why}</Text>
        <Text style={{ color: ok ? c.primary : c.amber, fontSize: t.small }}>
          {ok ? '✓ ' : ''}
          {state}
        </Text>
      </View>
      {ok === false && (
        <Button label="Turn on" kind="outlined" compact onPress={props.onFix} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.m,
    paddingVertical: space.s,
  },
});
