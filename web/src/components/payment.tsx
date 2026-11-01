import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { api } from '../lib/api';
import { formatPercent, formatRupees } from '../lib/format';
import { useT } from '../lib/i18n';
import { payAtCheckout } from '../lib/razorpay';
import type { Booking, PaymentMethod, PaymentTerms } from '../lib/types';
import { Button, Notice, SelectField } from './ui';

// The terms change only with a redeploy; one fetch per visit is enough.
export const usePaymentTerms = () =>
  useQuery({ queryKey: ['payment-terms'], queryFn: () => api<PaymentTerms>('/payment-terms'), staleTime: Infinity });

/**
 * Says, wherever money is mentioned, how real it is: simulated, Razorpay's test mode, or
 * real. To drivers it also says that payouts are recorded, never sent.
 */
export function SimulatedPaymentNotice({ forDrivers = false }: { forDrivers?: boolean }) {
  const t = useT();
  const terms = usePaymentTerms();
  const provider = terms.data?.provider ?? 'MOCK';
  if (provider === 'MOCK') {
    return (
      <Notice tone="info">
        {t('Payments here are simulated. No card, UPI or wallet is contacted and no money is taken or paid out.')}
      </Notice>
    );
  }
  const said = [
    provider === 'RAZORPAY_TEST' &&
      t('Payments go through Razorpay in test mode: pay with its test card or UPI details. No real money moves.'),
    forDrivers &&
      t('Passengers pay through Razorpay, but VAYORA does not pay drivers through it: what you are owed is recorded here, not sent.'),
  ].filter(Boolean);
  return said.length > 0 ? <Notice tone="info">{said.join(' ')}</Notice> : null;
}

/** What a passenger is told was taken, as the provider took it. */
export function paidLine(provider: string | undefined, amount: string, t: ReturnType<typeof useT>): string {
  if (provider === 'RAZORPAY_TEST') return t("{amount} paid in Razorpay's test mode: no real money moved.", { amount });
  if (provider === 'RAZORPAY') return t('{amount} paid through Razorpay.', { amount });
  return t('{amount} is held (simulated payment).', { amount });
}

/** What a passenger booking to pay after the ride is told: nothing was taken. */
export function laterLine(amount: string, t: ReturnType<typeof useT>): string {
  return t('Nothing is taken now: pay {amount} after the ride, in the app or in cash to the driver.', { amount });
}

/**
 * What a booking paid after the ride owes, and the way to pay it: in Razorpay's checkout
 * while payments go through it, otherwise by the method chosen here, as when booking.
 */
export function PayDue({ bookingId, amount, description }: { bookingId: number; amount: number; description: string }) {
  const t = useT();
  const queryClient = useQueryClient();
  const terms = usePaymentTerms();
  const checkout = terms.data?.checkout === true;
  const [method, setMethod] = useState<PaymentMethod>('UPI');
  const pay = useMutation({
    mutationFn: async () => {
      const razorpay = checkout ? await payAtCheckout(`/bookings/${bookingId}/checkout`, undefined, description) : undefined;
      return api<Booking>(`/bookings/${bookingId}/pay`, { method: 'POST', body: razorpay ? { razorpay } : { paymentMethod: method } });
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['bookings'] });
      void queryClient.invalidateQueries({ queryKey: ['payments'] });
    },
  });
  return (
    <div className="mt-3 space-y-2">
      <Notice tone="info">{t('{amount} is due. Pay it here, or in cash to your driver.', { amount: formatRupees(amount) })}</Notice>
      {pay.error && <Notice tone="error">{pay.error.message}</Notice>}
      <div className="flex flex-wrap items-end gap-2">
        {!checkout && (
          <SelectField label={t('Pay with')} value={method} onChange={(event) => setMethod(event.target.value as PaymentMethod)}>
            <option value="UPI">{t('UPI')}</option>
            <option value="CARD">{t('Card')}</option>
            <option value="WALLET">{t('Wallet')}</option>
          </SelectField>
        )}
        <Button disabled={pay.isPending} onClick={() => pay.mutate()}>
          {t('Pay {amount}', { amount: formatRupees(amount) })}
        </Button>
      </div>
    </div>
  );
}

/** The refund policy in words, built from what the server will actually apply. */
export function RefundTerms() {
  const t = useT();
  const terms = usePaymentTerms();
  if (!terms.data) return null;
  const tiers = terms.data.refundTiers;
  return (
    <div className="rounded-2xl border border-slate-200 bg-surface px-4 py-3 text-sm text-slate-700">
      <p className="font-medium text-slate-900">{t('If you cancel')}</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-5">
        <li>{t('Before the driver accepts: everything back, including the service fee.')}</li>
        {tiers.map((tier, index) => {
          const when =
            index === 0
              ? t('{hours} hours or more before boarding', { hours: tier.atLeastHoursBefore })
              : t('{from} to {to} hours before boarding', { from: tier.atLeastHoursBefore, to: tiers[index - 1].atLeastHoursBefore });
          return (
            <li key={tier.atLeastHoursBefore}>
              {tier.fareShare === 1
                ? t('{when}: the whole fare back.', { when })
                : t('{when}: {share} of the fare back.', { when, share: formatPercent(tier.fareShare) })}
            </li>
          );
        })}
        {tiers.length > 0 && (
          <li>{t('Less than {hours} hours before boarding: nothing back.', { hours: tiers[tiers.length - 1].atLeastHoursBefore })}</li>
        )}
        <li>{t('Once the driver has accepted, the service fee is not returned.')}</li>
        <li>{t('If the driver cancels or declines: everything back.')}</li>
      </ul>
    </div>
  );
}
