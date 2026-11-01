import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { api } from '../lib/api';
import { formatDate } from '../lib/format';
import { useT } from '../lib/i18n';
import type { Vehicle } from '../lib/types';
import { DocumentCheckStatus } from './documentCheck';
import { Button, Notice, TextField } from './ui';

/**
 * The insurance a driver declares for a car (feature 58). Nothing is checked with the insurer
 * and no policy number is asked for, and every place it is shown says so. Staff may be asked to
 * see the policy.
 */
export function InsuranceEditor({ vehicle }: { vehicle: Vehicle }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [insurer, setInsurer] = useState('');
  const [until, setUntil] = useState('');
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['vehicles'] });
  const declare = useMutation({
    mutationFn: () =>
      api<Vehicle>(`/vehicles/${vehicle.id}/insurance`, { method: 'PUT', body: { insurer: insurer.trim(), insuredUntil: until } }),
    onSuccess: () => {
      setInsurer('');
      setUntil('');
      refresh();
    },
  });
  const clear = useMutation({
    mutationFn: () => api<Vehicle>(`/vehicles/${vehicle.id}/insurance`, { method: 'DELETE' }),
    onSuccess: refresh,
  });

  if (vehicle.insurer && vehicle.insuredUntil) {
    return (
      <div className="mt-3 space-y-2 text-sm">
        <p className="text-slate-700">
          {t('Insurance: {insurer}, until {date}. As you declared it: VAYORA has not checked it with the insurer.', {
            insurer: vehicle.insurer,
            date: formatDate(vehicle.insuredUntil),
          })}
        </p>
        <DocumentCheckStatus kind="INSURANCE" vehicleId={vehicle.id} />
        <Button tone="secondary" disabled={clear.isPending} onClick={() => clear.mutate()}>
          {t('Remove the insurance')}
        </Button>
        {clear.error && <Notice tone="error">{clear.error.message}</Notice>}
      </div>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    declare.mutate();
  };

  return (
    <form onSubmit={submit} className="mt-3 grid items-end gap-3 sm:grid-cols-3" aria-label={t('Insurance')}>
      <TextField label={t('Insurer')} value={insurer} maxLength={60} onChange={(event) => setInsurer(event.target.value)} />
      <TextField label={t('Insured until')} type="date" value={until} onChange={(event) => setUntil(event.target.value)} />
      <Button type="submit" tone="secondary" disabled={declare.isPending || insurer.trim() === '' || until === ''}>
        {t('Declare insurance')}
      </Button>
      <p className="text-xs text-slate-500 sm:col-span-3">
        {t('Passengers see the insurer and the date, marked as not checked. No policy number is asked for.')}
      </p>
      {declare.error && (
        <div className="sm:col-span-3">
          <Notice tone="error">{declare.error.message}</Notice>
        </div>
      )}
    </form>
  );
}
