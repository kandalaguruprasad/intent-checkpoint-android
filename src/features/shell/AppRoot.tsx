import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, BackHandler, View } from 'react-native';
import { intentCheckpoint } from '../../services/intentCheckpoint';
import { usePalette, layout } from '../../theme/tokens';
import { AppsScreen } from '../apps/AppsScreen';
import { HistoryScreen } from '../history/HistoryScreen';
import { ChooseAppsScreen } from '../onboarding/ChooseAppsScreen';
import { SetupAccessScreen } from '../onboarding/SetupAccessScreen';
import { SettingsScreen } from '../settings/SettingsScreen';
import { TodayScreen } from '../today/TodayScreen';
import { TabBar, type Tab } from './TabBar';

type Route =
  | { name: 'loading' }
  | { name: 'onboarding-apps' }
  | { name: 'onboarding-access' }
  | { name: 'main' }
  | { name: 'edit-apps' };

/**
 * App shell: onboarding gate (stored in the engine's SQLite, so it survives RN process death),
 * then four tabs. Minimal in-house routing; a navigation library is a later, separate decision.
 */
export function AppRoot() {
  const c = usePalette();
  const [route, setRoute] = useState<Route>({ name: 'loading' });
  const [tab, setTab] = useState<Tab>('today');
  const [appsVersion, setAppsVersion] = useState(0);

  useEffect(() => {
    intentCheckpoint
      .isOnboardingComplete()
      .then(done =>
        setRoute(done ? { name: 'main' } : { name: 'onboarding-apps' }),
      )
      .catch(() => setRoute({ name: 'onboarding-apps' }));
  }, []);

  // Android back on a non-Today tab returns to Today before leaving the app.
  useEffect(() => {
    if (route.name !== 'main' || tab === 'today') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setTab('today');
      return true;
    });
    return () => sub.remove();
  }, [route.name, tab]);

  const toMain = useCallback(() => {
    setAppsVersion(v => v + 1);
    setRoute({ name: 'main' });
  }, []);

  switch (route.name) {
    case 'loading':
      return (
        <View style={[layout.flex1Center, { backgroundColor: c.background }]}>
          <ActivityIndicator color={c.primary} />
        </View>
      );
    case 'onboarding-apps':
      return (
        <ChooseAppsScreen
          mode="onboarding"
          step={{ index: 0, count: 2 }}
          onDone={() => setRoute({ name: 'onboarding-access' })}
        />
      );
    case 'onboarding-access':
      return <SetupAccessScreen onDone={toMain} />;
    case 'edit-apps':
      return (
        <ChooseAppsScreen
          mode="edit"
          onDone={toMain}
          onBack={() => setRoute({ name: 'main' })}
        />
      );
    case 'main':
      return (
        <View style={[layout.flex1, { backgroundColor: c.background }]}>
          <View style={layout.flex1}>
            {tab === 'today' && (
              <TodayScreen
                onManageApps={() => setTab('apps')}
                onOpenSettings={() => setTab('settings')}
              />
            )}
            {tab === 'history' && <HistoryScreen />}
            {tab === 'apps' && (
              <AppsScreen
                refreshKey={appsVersion}
                onEdit={() => setRoute({ name: 'edit-apps' })}
              />
            )}
            {tab === 'settings' && (
              <SettingsScreen
                onPreviewChooseApps={() => setRoute({ name: 'edit-apps' })}
                onRerunSetup={() => {
                  intentCheckpoint.setOnboardingComplete(false).catch(() => {});
                  setRoute({ name: 'onboarding-apps' });
                }}
              />
            )}
          </View>
          <TabBar tab={tab} onChange={setTab} />
        </View>
      );
  }
}
