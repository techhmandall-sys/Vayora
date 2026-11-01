import { TriangleAlert } from 'lucide-react';
import { Component, type ErrorInfo, type ReactNode } from 'react';

import { useT } from '../lib/i18n';
import { isFailedImport } from '../lib/staleBuild';
import { Button } from './ui';

function Fallback({ retry, reload }: { retry: () => void; reload?: () => void }) {
  const t = useT();
  return (
    <div role="alert" className="flex flex-col items-center gap-4 rounded-3xl bg-surface px-6 py-12 text-center shadow-card ring-1 ring-slate-900/[0.04]">
      <span aria-hidden="true" className="flex h-16 w-16 items-center justify-center rounded-[22px] bg-amber-100 text-amber-800">
        <TriangleAlert className="h-8 w-8" />
      </span>
      <p className="max-w-sm text-[15px] font-medium text-slate-800">
        {reload
          ? t('This screen could not be loaded. VAYORA may have been updated: reload to get the new version.')
          : t('Something went wrong on this screen.')}
      </p>
      {reload ? <Button onClick={reload}>{t('Reload')}</Button> : <Button onClick={retry}>{t('Try again')}</Button>}
    </div>
  );
}

/**
 * Keeps one broken screen from blanking the whole app: the header, the emergency button
 * and every other page keep working. Keyed by the path where it is used, so moving to
 * another page clears it.
 *
 * A screen whose code failed to load cannot be retried in place, since the failed load is
 * remembered: only loading the page again fetches it.
 */
export class ErrorBoundary extends Component<
  { children: ReactNode; reloadPage?: () => void },
  { failed: boolean; failedImport: boolean }
> {
  state = { failed: false, failedImport: false };

  static getDerivedStateFromError(error: unknown) {
    return { failed: true, failedImport: isFailedImport(error) };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('A screen failed to render', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    const reload = this.props.reloadPage ?? (() => window.location.reload());
    return (
      <Fallback
        retry={() => this.setState({ failed: false, failedImport: false })}
        reload={this.state.failedImport ? reload : undefined}
      />
    );
  }
}
