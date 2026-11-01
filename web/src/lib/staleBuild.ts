// After a new release the build's file names change, and a page opened before it asks for a
// screen's code under a name that no longer exists. Loading the page again fetches the new
// release. Vite reports the failure as `vite:preloadError`.

/** Whether an error is a screen's code failing to load, as browsers word it. */
export function isFailedImport(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /dynamically imported module|Importing a module script failed|Unable to preload CSS/i.test(message);
}

interface Host {
  location: { reload(): void };
  sessionStorage: Storage;
  addEventListener(type: string, listener: (event: Event) => void): void;
}

const RELOADED_AT = 'vayora.reloadedForNewRelease';
// A second failure this soon after reloading is not a new release: the code is missing for
// another reason (offline, server down), and reloading again would only loop.
const AGAIN_AFTER_MS = 60_000;

/** Loads the page again, unless it was just done. Returns whether it reloaded. */
export function reloadForNewRelease(host: Host = window, now: number = Date.now()): boolean {
  try {
    const last = Number(host.sessionStorage.getItem(RELOADED_AT));
    if (last && now - last < AGAIN_AFTER_MS) return false;
    host.sessionStorage.setItem(RELOADED_AT, String(now));
  } catch {
    return false; // without storage one reload cannot be told from the next
  }
  host.location.reload();
  return true;
}

/** Reloads once when a screen's code is missing; otherwise the screen's own error shows. */
export function reloadOnStaleBuild(host: Host = window): void {
  host.addEventListener('vite:preloadError', (event) => {
    if (reloadForNewRelease(host)) event.preventDefault();
  });
}
