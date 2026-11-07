import { useMemo, useSyncExternalStore } from 'react';
import { Controller } from '../shira/controller';
import { REPO_URL } from '../shira/constants';
import { Monitor } from './Monitor';
import { Setup } from './Setup';

export function App() {
  const controller = useMemo(() => new Controller(), []);
  const state = useSyncExternalStore(controller.subscribe, controller.getState);
  return (
    <div className="app">
      <header className="app-bar">
        <img className="app-bar-logo" src="./logo.webp" alt="" />
        <span className="app-bar-title">Shira</span>
        <span className="app-bar-space" />
        <mdui-button variant="text" href={REPO_URL} target="_blank" rel="noreferrer">
          GitHub
          <mdui-icon-open-in-new slot="end-icon" />
        </mdui-button>
      </header>
      {state.step === 'live' ? <Monitor controller={controller} state={state} /> : <Setup controller={controller} state={state} />}
    </div>
  );
}
