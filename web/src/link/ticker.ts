// Runs in a worker, whose timers keep their pace when the tab is hidden.
self.onmessage = (event: MessageEvent<number>) => {
  setInterval(() => self.postMessage(null), event.data);
};
