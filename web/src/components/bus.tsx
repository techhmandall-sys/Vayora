import { useQuery } from '@tanstack/react-query';

import { Card } from './ui';
import { api } from '../lib/api';
import { formatRupees } from '../lib/format';
import { useT } from '../lib/i18n';
import type { BusService } from '../lib/types';

/**
 * Buses between two towns, offered when no ride will do. Staff enter them; nothing checks
 * them against the operator, so the passenger is told to. Shows nothing if none are listed.
 */
export function BusAlternatives({ from, to }: { from: string; to: string }) {
  const t = useT();
  const buses = useQuery({
    queryKey: ['bus-alternatives', from, to],
    queryFn: () => api<BusService[]>(`/bus-alternatives?${new URLSearchParams({ from, to })}`),
  });
  if (!buses.data || buses.data.length === 0) {
    return null;
  }
  return (
    <section aria-label={t('Buses instead')}>
      <Card>
        <h2 className="font-semibold">{t('Buses instead')}</h2>
        <p className="mt-1 text-sm text-slate-600">
          {t('Listed by VAYORA staff, not by the bus operator. Check times and fares with the operator before you travel.')}
        </p>
        <ul className="mt-3 space-y-1 text-sm">
          {buses.data.map((bus) => (
            <li key={bus.id}>
              <span className="font-medium">{bus.departs.slice(0, 5)}</span> · {bus.operator} ·{' '}
              {t('about {hours} h', { hours: (bus.durationMinutes / 60).toFixed(1) })} · {formatRupees(bus.fare)}
              {bus.notes && <span className="text-slate-600"> · {bus.notes}</span>}
            </li>
          ))}
        </ul>
      </Card>
    </section>
  );
}
