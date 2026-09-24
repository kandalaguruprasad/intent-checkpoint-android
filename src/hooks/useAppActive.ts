import { useEffect } from 'react';
import { AppState } from 'react-native';

/** Calls [onActive] whenever the app returns to the foreground (e.g. back from Settings). */
export function useAppActive(onActive: () => void): void {
  useEffect(() => {
    const sub = AppState.addEventListener('change', state => {
      if (state === 'active') onActive();
    });
    return () => sub.remove();
  }, [onActive]);
}
