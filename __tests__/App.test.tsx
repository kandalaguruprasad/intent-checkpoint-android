/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);

jest.mock('../src/native/NativeIntentCheckpoint', () => {
  // Defined inside the factory: jest hoists jest.mock above top-level consts.
  const native = {
    isOnboardingComplete: jest.fn(async () => true),
    getPermissionState: jest.fn(async () => ({
      usageAccess: true,
      overlay: false,
      notifications: true,
      batteryUnrestricted: false,
      exactAlarms: false,
    })),
    getMonitoringStatus: jest.fn(async () => ({
      enabled: true,
      running: true,
      monitoredPackages: ['com.instagram.android'],
    })),
    getSummary: jest.fn(async () => ({
      opensNoticed: 2,
      intentionalSessions: 1,
      choseNotToOpen: 1,
      plannedSeconds: 600,
      actualSecondsTimed: 240,
      actualSecondsAll: 240,
      extensions: 0,
      finishedOnTime: 1,
      perApp: [
        {
          packageName: 'com.instagram.android',
          appName: 'Instagram',
          opens: 2,
          actualSeconds: 240,
        },
      ],
    })),
    getActiveSession: jest.fn(async () => null),
    getMonitoredApps: jest.fn(async () => [
      {
        packageName: 'com.instagram.android',
        appName: 'Instagram',
        category: 'social',
        icon: null,
      },
    ]),
    listLaunchableApps: jest.fn(async () => [
      {
        packageName: 'com.instagram.android',
        label: 'Instagram',
        category: 'social',
        sensitive: false,
        icon: null,
      },
      {
        packageName: 'com.phonepe.app',
        label: 'PhonePe',
        category: 'other',
        sensitive: true,
        icon: null,
      },
    ]),
    getLatencyStats: jest.fn(async () => ({
      count: 0,
      medianMs: null,
      p95Ms: null,
      maxMs: null,
    })),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  };
  return { __esModule: true, default: native };
});

const mockNative = jest.requireMock('../src/native/NativeIntentCheckpoint')
  .default as {
  isOnboardingComplete: jest.Mock;
};

type Node = ReactTestRenderer.ReactTestRendererJSON | string | null;

/** All rendered text, joined; avoids serialising props (some hold circular refs). */
function textOf(node: Node | Node[]): string {
  if (node == null) return '';
  if (typeof node === 'string') return node;
  if (Array.isArray(node)) return node.map(textOf).join('|');
  return (node.children ?? []).map(n => textOf(n as Node)).join('|');
}

let tree: ReactTestRenderer.ReactTestRenderer | undefined;

afterEach(() => {
  ReactTestRenderer.act(() => tree?.unmount());
  tree = undefined;
});

async function render() {
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  // let the chained promises (onboarding → data) settle
  await ReactTestRenderer.act(async () => {});
  return textOf(tree!.toJSON() as Node);
}

test('Today renders real numbers from the engine and flags a missing permission', async () => {
  const text = await render();
  expect(text).toContain('Intent');
  expect(text).toContain('Opens noticed');
  expect(text).toContain('|2|'); // opens from getSummary, not a hardcoded value
  expect(text).toContain('Needs attention');
  expect(text).toContain("Intent can't pause apps right now");
  expect(text).toContain('Instagram');
  expect(text).toContain('4 min'); // actual 240 s
  expect(text).not.toContain('|8|'); // design-image sample value must not leak in
});

test('first launch goes to Choose Apps with the sensitive group separated', async () => {
  mockNative.isOnboardingComplete.mockResolvedValueOnce(false);
  const text = await render();
  expect(text).toContain('Which apps pull you in?');
  expect(text).toContain('Start with one or two apps');
  expect(text).toContain('Not recommended');
  expect(text).toContain('PhonePe');
  expect(text).toContain('1 app selected'); // preselects what is already monitored
});
