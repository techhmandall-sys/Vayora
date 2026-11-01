import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { isFailedImport, reloadForNewRelease, reloadOnStaleBuild } from '../lib/staleBuild';
import { ErrorBoundary } from './ErrorBoundary';

function host() {
  const listeners: Record<string, (event: Event) => void> = {};
  return {
    location: { reload: vi.fn() },
    sessionStorage: window.sessionStorage,
    addEventListener: (type: string, listener: (event: Event) => void) => {
      listeners[type] = listener;
    },
    fire: (type: string) => {
      const event = new Event(type, { cancelable: true });
      listeners[type]?.(event);
      return event;
    },
  };
}

function Broken({ error }: { error: Error }): never {
  throw error;
}

describe('a release made while a page was open', () => {
  it('reloads once to fetch the new release, and not again straight away', () => {
    const page = host();
    reloadOnStaleBuild(page);

    expect(page.fire('vite:preloadError').defaultPrevented).toBe(true);
    expect(page.location.reload).toHaveBeenCalledTimes(1);
    // Missing again at once: not a new release, so the screen's error is shown instead.
    expect(page.fire('vite:preloadError').defaultPrevented).toBe(false);
    expect(page.location.reload).toHaveBeenCalledTimes(1);
  });

  it('reloads for a later release once a minute has passed', () => {
    const page = host();

    expect(reloadForNewRelease(page, 1_000_000)).toBe(true);
    expect(reloadForNewRelease(page, 1_030_000)).toBe(false);
    expect(reloadForNewRelease(page, 1_070_000)).toBe(true);
    expect(page.location.reload).toHaveBeenCalledTimes(2);
  });

  it('tells a screen whose code failed to load from one that failed to render', () => {
    expect(isFailedImport(new TypeError('Failed to fetch dynamically imported module: /assets/DriverPages-D6PA46w3.js'))).toBe(true);
    expect(isFailedImport(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isFailedImport(new TypeError("Cannot read properties of undefined (reading 'map')"))).toBe(false);
  });

  it('offers a real reload for a screen whose code is missing, since trying again in place cannot work', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const reloadPage = vi.fn();
    render(
      <ErrorBoundary reloadPage={reloadPage}>
        <Broken error={new TypeError('Failed to fetch dynamically imported module: /assets/DriverPages-D6PA46w3.js')} />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('VAYORA may have been updated');
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(reloadPage).toHaveBeenCalledTimes(1);
  });

  it('still offers to try again in place for any other failure', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(
      <ErrorBoundary reloadPage={vi.fn()}>
        <Broken error={new Error('boom')} />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong on this screen.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
