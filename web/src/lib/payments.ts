import { msg } from './i18n';
import type { PaidMethod, RefundReason } from './types';

/** How a payment was made, wherever it is named. */
export const methodLabels: Record<PaidMethod, string> = {
  CARD: msg('Card'),
  UPI: msg('UPI'),
  WALLET: msg('Wallet'),
  CASH: msg('Cash to the driver'),
};

/** Why money came back, told to the passenger it came back to. */
export const refundReasons: Record<RefundReason, string> = {
  NOT_ACCEPTED: msg('The driver did not accept'),
  DRIVER_CANCELLED: msg('The driver cancelled'),
  PASSENGER_CANCELLED: msg('You cancelled'),
};
