import { useQuery } from '@tanstack/react-query';
import { Wallet } from 'lucide-react';

import { SimulatedPaymentNotice } from '../components/payment';
import { ReceiptLinks } from '../components/receipt';
import { Card, Page, QueryState } from '../components/ui';
import { api } from '../lib/api';
import { formatDateTime, formatRupees } from '../lib/format';
import { useT } from '../lib/i18n';
import { methodLabels, refundReasons } from '../lib/payments';
import type { PassengerPayment } from '../lib/types';

/** What was actually taken: for a booking paid after the ride, what it owed, once paid. */
function taken(payment: PassengerPayment): number {
  if (!payment.payAfterRide) return payment.amount - payment.refunded;
  return payment.status === 'SETTLED' ? (payment.amountDue ?? 0) : 0;
}

/** What a passenger has paid, and what came back, newest first. */
export function PaymentsPage() {
  const t = useT();
  const payments = useQuery({ queryKey: ['payments'], queryFn: () => api<PassengerPayment[]>('/payments') });
  const paid = payments.data?.reduce((sum, payment) => sum + taken(payment), 0) ?? 0;

  return (
    <Page title={t('Payments')}>
      <SimulatedPaymentNotice />
      <QueryState
        isPending={payments.isPending}
        error={payments.error}
        isEmpty={payments.data?.length === 0}
        empty={t('You have not paid for anything yet.')}
        icon={Wallet}
        onRetry={() => void payments.refetch()}
      />
      {payments.data && payments.data.length > 0 && (
        <Card>
          <p className="text-sm text-slate-600">{t('Paid, less what came back')}</p>
          <p className="text-2xl font-semibold">{formatRupees(paid)}</p>
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{t('Payments, newest first')}</caption>
              <thead className="border-b border-slate-200 text-slate-600">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">{t('Booked')}</th>
                  <th scope="col" className="py-2 pr-4 font-medium">{t('Trip')}</th>
                  <th scope="col" className="py-2 pr-4 font-medium">{t('Paid with')}</th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">{t('Amount')}</th>
                  <th scope="col" className="py-2 pr-4 font-medium">{t('Came back')}</th>
                  <th scope="col" className="py-2 font-medium">
                    <span className="sr-only">{t('Receipt')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {payments.data.map((payment) => (
                  <tr key={payment.bookingId} className="border-b border-slate-100 align-top last:border-0">
                    <td className="py-2 pr-4">{formatDateTime(payment.createdAt)}</td>
                    <td className="py-2 pr-4">
                      {t(payment.fromStop)} → {t(payment.toStop)}
                    </td>
                    <td className="py-2 pr-4">
                      {payment.method ? t(methodLabels[payment.method]) : payment.status === 'VOID' ? '—' : t('Not paid yet')}
                    </td>
                    <td className="py-2 pr-4 text-right">
                      <span className="font-medium">{formatRupees(payment.amount)}</span>
                      <span className="block text-xs text-slate-500">
                        {t('{fare} fare + {fee} service fee', { fare: formatRupees(payment.fare), fee: formatRupees(payment.serviceFee) })}
                      </span>
                    </td>
                    <td className="py-2 pr-4">
                      {payment.payAfterRide
                        ? payment.status === 'DUE' && payment.amountDue != null
                          ? t('{amount} due: pay it in My bookings', { amount: formatRupees(payment.amountDue) })
                          : payment.status === 'SETTLED' && payment.amountDue != null
                            ? t('{amount} paid after the ride', { amount: formatRupees(payment.amountDue) })
                            : payment.status === 'VOID'
                              ? t('Nothing owed')
                              : t('Pay after the ride')
                        : payment.refunded > 0 && payment.refundReason
                          ? `${formatRupees(payment.refunded)}: ${t(refundReasons[payment.refundReason])}`
                          : payment.status === 'HELD'
                            ? t('Held until the trip ends')
                            : t('Nothing')}
                    </td>
                    <td className="py-2">
                      {/* Paid after the ride, there is a receipt once it is paid and not before. */}
                      {(!payment.payAfterRide || payment.status === 'SETTLED') && <ReceiptLinks of="booking" id={payment.bookingId} />}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </Page>
  );
}
