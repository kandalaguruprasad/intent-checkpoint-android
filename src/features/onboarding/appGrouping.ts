import type { AppCategory, LaunchableApp } from '../../native/types';

export const CATEGORY_LABELS: Record<AppCategory, string> = {
  social: 'Social',
  video: 'Video and music',
  games: 'Games',
  shopping: 'Shopping',
  news: 'News and reading',
  browsers: 'Browsers',
  other: 'Other apps',
};

const ORDER: AppCategory[] = [
  'social',
  'video',
  'games',
  'shopping',
  'news',
  'browsers',
  'other',
];

export interface AppSection {
  key: string;
  title: string;
  note?: string;
  data: LaunchableApp[];
}

export const SENSITIVE_NOTE =
  'Banking, payment, authenticator and password apps. We recommend not adding these: a pause in front of a payment or a login code only gets in the way.';

/**
 * Groups apps by category for the picker. Sensitive apps are pulled into their own last section
 * so the recommendation is visible without hiding anything. Search matches label or package.
 */
export function groupApps(apps: LaunchableApp[], query: string): AppSection[] {
  const q = query.trim().toLowerCase();
  const matches = q
    ? apps.filter(
        a =>
          a.label.toLowerCase().includes(q) ||
          a.packageName.toLowerCase().includes(q),
      )
    : apps;
  const sections: AppSection[] = ORDER.map(cat => ({
    key: cat,
    title: CATEGORY_LABELS[cat],
    data: matches.filter(a => a.category === cat && !a.sensitive),
  })).filter(s => s.data.length > 0);
  const sensitive = matches.filter(a => a.sensitive);
  if (sensitive.length > 0) {
    sections.push({
      key: 'sensitive',
      title: 'Not recommended',
      note: SENSITIVE_NOTE,
      data: sensitive,
    });
  }
  return sections;
}
