/**
 * @format
 */

import { StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AppRoot } from './src/features/shell/AppRoot';
import { PreviewProvider } from './src/features/shell/preview';

function App() {
  return (
    <SafeAreaProvider>
      <PreviewProvider>
        {/* Fixed light theme (src/theme/tokens.ts): status bar text is always dark. */}
        <StatusBar barStyle="dark-content" />
        <AppRoot />
      </PreviewProvider>
    </SafeAreaProvider>
  );
}

export default App;
