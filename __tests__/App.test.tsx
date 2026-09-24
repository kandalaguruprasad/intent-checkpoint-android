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

jest.mock('../src/native/NativeIntentCheckpoint', () => ({
  __esModule: true,
  default: {
    getPermissionState: jest.fn(async () => ({
      usageAccess: false,
      overlay: false,
      notifications: true,
      batteryUnrestricted: false,
      exactAlarms: false,
    })),
    getMonitoringStatus: jest.fn(async () => ({
      enabled: false,
      running: false,
      monitoredPackages: ['com.instagram.android'],
    })),
    getActiveSession: jest.fn(async () => null),
    getLatencyStats: jest.fn(async () => ({
      count: 0,
      medianMs: null,
      p95Ms: null,
      maxMs: null,
    })),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

test('renders the Phase 0 screen and reads native state', async () => {
  let tree: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  const text = JSON.stringify(tree!.toJSON());
  expect(text).toContain('Permission health');
  expect(text).toContain('com.instagram.android');
  expect(text).toContain(
    'Grant Usage Access and Display over other apps first.',
  );
});
