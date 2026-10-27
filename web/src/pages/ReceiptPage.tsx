import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useParams } from 'react-router-dom';

import { Button, Card, Page, QueryState, statusText } from '../components/ui';
import { api } from '../lib/api';
import { formatExactRupees, formatFullDateTime } from '../lib/format';
import { useT, type Translate } from '../lib/i18n';
import { passKindLabels } from '../lib/passes';
import { methodLabels, refundReasons } from '../lib/payments';
import type { BookingReceipt, PassReceipt } from '../lib/types';

/**
 * Receipts in the reader's language (feature 50): the same facts as the PDF, with names and
 * stops as written, in any script. The browser prints it, or saves it as a PDF, with its
 * own fonts, which the server's PDF cannot do.
 */
export function BookingReceiptPage() {
  const t = useT();
  const { id = '' } = useParams();
  const receipt = useQuery({ queryKey: ['receipt', 'booking', id], queryFn: () => api<BookingReceipt>(`/bookings/${id}/receipt-data`) });
  const r = receipt.data;
  return (
    <Page title={t('Receipt for booking {id}', { id })}>
      <QueryState isPending={receipt.isPending} error={receipt.error} isEmpty={false} empty="" onRetry={() => void receipt.refetch()} />
      {r && (
        <Receipt provider={r.provider}>
          <Line label={t('Passenger')} value={r.passenger} />
          <Line label={t('Driver')} value={r.driver} />
          <Line
            label={t('Trip')}
            value={
              r.seats === 1
                ? t('{from} to {to}, {n} seat', { from: t(r.fromStop), to: t(r.toStop), n: r.seats })
                : t('{from} to {to}, {n} seats', { from: t(r.fromStop), to: t(r.toStop), n: r.seats })
            }
          />
          <Line label={t('Boarding')} value={formatFullDateTime(r.boarding)} />
          <Line label={t('Booking status')} value={t(statusText(r.status))} />
          <Line label={t('Paid')} value={t('{when}, by {method}', { when: formatFullDateTime(r.paidAt), method: t(methodLabels[r.method]) })} />
          <Line label={t('Fare')} value={formatExactRupees(r.fare)} />
          <Line label={t('Service fee')} value={formatExactRupees(r.serviceFee)} />
          <Line label={t('Total')} value={formatExactRupees(r.total)} strong />
          {r.refunded > 0 && r.refundReason && (
            <Line label={t('Refunded')} value={`${formatExactRupees(r.refunded)}: ${t(refundReasons[r.refundReason])}`} />
          )}
        </Receipt>
      )}
    </Page>
  );
}

export function PassReceiptPage() {
  const t = useT();
  const { id = '' } = useParams();
  const receipt = useQuery({ queryKey: ['receipt', 'pass', id], queryFn: () => api<PassReceipt>(`/passes/${id}/receipt-data`) });
  const r = receipt.data;
  return (
    <Page title={t('Receipt for travel pass {id}', { id })}>
      <QueryState isPending={receipt.isPending} error={receipt.error} isEmpty={false} empty="" onRetry={() => void receipt.refetch()} />
      {r && (
        // A reward was paid through nothing, so there is no provider to name.
        <Receipt
          provider={r.reward ? null : r.provider}
          notes={[
            ...(r.reward ? [t('Given free on {date} as a reward for an invitation. Nothing was paid.', { date: formatFullDateTime(r.paidAt) })] : []),
            t('No service fee is charged on bookings requested while it runs.'),
          ]}
        >
          <Line label={t('Passenger')} value={r.passenger} />
          <Line label={t('Pass')} value={t(passKindLabels[r.kind])} />
          <Line label={t('Period')} value={t('{start} until {end}', { start: formatFullDateTime(r.startsAt), end: formatFullDateTime(r.endsAt) })} />
          {r.method && !r.reward && (
            <Line label={t('Paid')} value={t('{when}, by {method}', { when: formatFullDateTime(r.paidAt), method: t(methodLabels[r.method]) })} />
          )}
          <Line label={t('Total')} value={formatExactRupees(r.price)} strong />
        </Receipt>
      )}
    </Page>
  );
}

function Receipt({ provider, notes = [], children }: { provider: string | null; notes?: string[]; children: ReactNode }) {
  const t = useT();
  return (
    <>
      <Card>
        <dl className="text-sm">{children}</dl>
        {notes.map((note) => (
          <p key={note} className="mt-2 text-sm text-slate-700">
            {note}
          </p>
        ))}
        <div className="mt-4 space-y-1 border-t border-slate-200 pt-3 text-sm text-slate-600">
          {provider && <p className="font-medium text-slate-900">{providerStatement(provider, t)}</p>}
          <p>{t('This is not a tax invoice.')}</p>
          <p>{t('Times are India Standard Time.')}</p>
          <p>{t('VAYORA, Kolhapur to Pune')}</p>
        </div>
      </Card>
      <div className="flex flex-wrap items-center gap-3 print:hidden">
        <Button onClick={() => window.print()}>{t('Print or save as PDF')}</Button>
        <p className="text-sm text-slate-600">{t('To keep a copy, choose to save as PDF instead of a printer.')}</p>
      </div>
    </>
  );
}

function Line({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-100 py-1.5 last:border-0">
      <dt className="text-slate-600">{label}</dt>
      <dd className={strong ? 'text-right font-semibold' : 'text-right'}>{value}</dd>
    </div>
  );
}

/** How real the money was, as every receipt must say. */
function providerStatement(provider: string, t: Translate): string {
  if (provider === 'MOCK') return t('Simulated payment: no money was taken or paid. This is a student project.');
  if (provider === 'RAZORPAY_TEST') return t("Paid in Razorpay's test mode, where no real money moves. This is a student project.");
  if (provider === 'RAZORPAY') return t('Paid through Razorpay.');
  return t('Payment provider: {provider}', { provider });
}
