/**
 * Fixture data for the design-preview mode (Settings → Preview screens). Never used when live
 * data is on; the Today screen always derives its numbers from persisted sessions.
 */
import type {
  DailySummary,
  LaunchableApp,
  MonitoredApp,
  SessionRecord,
} from '../native/types';

const now = Date.now();

export const fixtureMonitored: MonitoredApp[] = [
  {
    packageName: 'com.instagram.android',
    appName: 'Instagram',
    category: 'social',
    icon: null,
  },
  {
    packageName: 'com.reddit.frontpage',
    appName: 'Reddit',
    category: 'social',
    icon: null,
  },
  {
    packageName: 'com.google.android.youtube',
    appName: 'YouTube',
    category: 'video',
    icon: null,
  },
];

export const fixtureSummary: DailySummary = {
  opensNoticed: 8,
  intentionalSessions: 5,
  choseNotToOpen: 3,
  plannedSeconds: 50 * 60,
  actualSecondsTimed: 28 * 60,
  actualSecondsAll: 31 * 60,
  extensions: 1,
  finishedOnTime: 3,
  perApp: [
    {
      packageName: 'com.instagram.android',
      appName: 'Instagram',
      opens: 4,
      actualSeconds: 16 * 60,
    },
    {
      packageName: 'com.reddit.frontpage',
      appName: 'Reddit',
      opens: 2,
      actualSeconds: 9 * 60,
    },
    {
      packageName: 'com.google.android.youtube',
      appName: 'YouTube',
      opens: 2,
      actualSeconds: 6 * 60,
    },
  ],
};

export const fixtureActiveSession: SessionRecord = {
  id: 'fixture',
  packageName: 'com.instagram.android',
  appName: 'Instagram',
  intention: 'Reply to a message',
  startedAt: now - 78_000,
  plannedDurationSeconds: 600,
  plannedEndAt: now + 522_000,
  endedAt: null,
  wallClockSeconds: null,
  foregroundSeconds: 78,
  extensionCount: 0,
  state: 'SESSION_ACTIVE',
  completionReason: null,
  createdAt: now - 80_000,
  updatedAt: now - 78_000,
};

export const fixtureLaunchable: LaunchableApp[] = [
  {
    packageName: 'com.instagram.android',
    label: 'Instagram',
    category: 'social',
    sensitive: false,
    icon: null,
  },
  {
    packageName: 'com.reddit.frontpage',
    label: 'Reddit',
    category: 'social',
    sensitive: false,
    icon: null,
  },
  {
    packageName: 'com.google.android.youtube',
    label: 'YouTube',
    category: 'video',
    sensitive: false,
    icon: null,
  },
  {
    packageName: 'com.android.chrome',
    label: 'Chrome',
    category: 'browsers',
    sensitive: false,
    icon: null,
  },
  {
    packageName: 'com.flipkart.android',
    label: 'Flipkart',
    category: 'shopping',
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
];
