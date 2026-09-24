import React, { createContext, useContext, useMemo, useState } from 'react';

/**
 * Design-preview switch (Settings → "Preview screens with sample data"). When on, Today and Choose
 * Apps render fixture data so layouts can be reviewed without real usage. Off by default and never
 * persisted: live data is always the default.
 */
const PreviewContext = createContext<{
  fixtures: boolean;
  setFixtures: (v: boolean) => void;
}>({
  fixtures: false,
  setFixtures: () => {},
});

export function PreviewProvider({ children }: { children: React.ReactNode }) {
  const [fixtures, setFixtures] = useState(false);
  const value = useMemo(() => ({ fixtures, setFixtures }), [fixtures]);
  return (
    <PreviewContext.Provider value={value}>{children}</PreviewContext.Provider>
  );
}

export const usePreview = () => useContext(PreviewContext);
