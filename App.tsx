/**
 * @format
 */

import { StatusBar, useColorScheme } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AppRoot } from './src/features/shell/AppRoot';
import { PreviewProvider } from './src/features/shell/preview';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <SafeAreaProvider>
      <PreviewProvider>
        <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
        <AppRoot />
      </PreviewProvider>
    </SafeAreaProvider>
  );
}

export default App;
