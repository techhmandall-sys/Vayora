import { useMutation } from '@tanstack/react-query';

import { api } from '../lib/api';
import { useT } from '../lib/i18n';
import type { SosRaised } from '../lib/types';
import { Button } from './ui';

const EMERGENCY_NUMBER = '112';

/** Where the device says it is, or nothing: an alert must not wait long for a position. */
function position(): Promise<{ lat: number; lon: number } | null> {
  return new Promise((resolve) => {
    if (!('geolocation' in navigator)) return resolve(null);
    navigator.geolocation.getCurrentPosition(
      (found) => resolve({ lat: found.coords.latitude, lon: found.coords.longitude }),
      () => resolve(null),
      { timeout: 3000, maximumAge: 60_000 },
    );
  });
}

/**
 * The emergency panel. It puts calling the emergency number first, because an alert here
 * reaches an administrator's queue and, while messages are simulated, nobody else.
 */
export function SosPanel({ onClose }: { onClose: () => void }) {
  const t = useT();
  const raise = useMutation({
    mutationFn: async () => api<SosRaised>('/sos', { method: 'POST', body: (await position()) ?? {} }),
  });
  const raised = raise.data;

  return (
    <section aria-label={t('Emergency')} className="mx-auto max-w-5xl animate-sheet-in px-4 pt-4 sm:px-6">
      <div className="space-y-3 rounded-3xl bg-red-50 p-5 text-sm text-red-900 shadow-float ring-1 ring-inset ring-red-200">
        <p className="text-lg font-bold">{t('If you are in danger, call 112 now.')}</p>
        <p>
          <a
            href={`tel:${EMERGENCY_NUMBER}`}
            className="inline-flex items-center rounded-full bg-danger px-6 py-3 text-base font-bold text-white shadow-lg shadow-danger/30 transition-transform hover:bg-danger-strong active:scale-[0.96] focus:outline-none focus-visible:ring-4 focus-visible:ring-red-600/40"
          >
            {t('Call 112')}
          </a>
        </p>
        {!raised && (
          <>
            <p>{t('You can also alert the VAYORA administrators. They see it in a queue; they are not an emergency service.')}</p>
            {raise.error && <p role="alert">{raise.error.message}</p>}
            <div className="flex flex-wrap gap-2">
              <Button tone="danger" disabled={raise.isPending} onClick={() => raise.mutate()}>
                {raise.isPending ? t('Sending…') : t('Send an alert')}
              </Button>
              <Button tone="secondary" onClick={onClose}>
                {t('Close')}
              </Button>
            </div>
          </>
        )}
        {raised && (
          <div role="status" className="space-y-2">
            <p className="font-medium">{t('Your alert is recorded and the administrators can see it.')}</p>
            {raised.reallySent ? (
              <>
                <p>{t('A message was sent to {n} of your emergency contacts.', { n: raised.contactsTold })}</p>
                {(raised.contactsMissed ?? 0) > 0 && (
                  <p className="font-semibold">
                    {t('{n} of your emergency contacts could not be messaged. Call them yourself, or call 112.', { n: raised.contactsMissed ?? 0 })}
                  </p>
                )}
              </>
            ) : (
              <p className="font-semibold">
                {t('Text messages are simulated in this project: your emergency contacts were NOT messaged. Call them yourself, or call 112.')}
              </p>
            )}
            <Button tone="secondary" onClick={onClose}>
              {t('Close')}
            </Button>
          </div>
        )}
      </div>
    </section>
  );
}
