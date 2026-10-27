import { api } from './api';
import { msg } from './i18n';

// Razorpay's checkout (feature 46), used only while payments go through Razorpay. Its
// script is Razorpay's own and is loaded the first time someone pays, never before.

/** An order core-api opened for what is due. The amount is in paise. */
interface Order {
  orderId: string;
  keyId: string;
  amount: number;
  currency: string;
  testMode: boolean;
}

/** What the checkout hands back once the passenger has paid; core-api checks all three. */
export interface CheckoutAnswer {
  orderId: string;
  paymentId: string;
  signature: string;
}

type Razorpay = new (options: Record<string, unknown>) => { open(): void };

const SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';
export const NOT_LOADED = msg("Razorpay's checkout could not be loaded. Check your connection and try again.");
export const NOT_PAID = msg('The payment was not completed. Nothing was bought.');

let loading: Promise<Razorpay> | null = null;

function load(): Promise<Razorpay> {
  const ready = (window as { Razorpay?: Razorpay }).Razorpay;
  if (ready) return Promise.resolve(ready);
  loading ??= new Promise<Razorpay>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SCRIPT;
    script.onload = () => {
      const loaded = (window as { Razorpay?: Razorpay }).Razorpay;
      if (loaded) resolve(loaded);
      else reject(new Error(NOT_LOADED));
    };
    script.onerror = () => {
      loading = null;
      script.remove();
      reject(new Error(NOT_LOADED));
    };
    document.head.appendChild(script);
  });
  return loading;
}

/**
 * Asks core-api to open an order for what is due, then opens Razorpay's checkout for it.
 * Resolves with the checkout's answer once the passenger has paid; rejects if they close
 * it without paying. Nothing is booked or bought until core-api has checked the answer.
 */
export async function payAtCheckout(path: string, body: unknown, description: string): Promise<CheckoutAnswer> {
  const order = await api<Order>(path, { method: 'POST', body });
  const Checkout = await load();
  return new Promise((resolve, reject) => {
    new Checkout({
      key: order.keyId,
      order_id: order.orderId,
      amount: order.amount,
      currency: order.currency,
      name: 'VAYORA',
      description,
      // Only the ways the platform records: cards, UPI and wallets.
      method: { netbanking: false, emi: false, cardless_emi: false, paylater: false },
      handler: (paid: { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }) =>
        resolve({ orderId: paid.razorpay_order_id, paymentId: paid.razorpay_payment_id, signature: paid.razorpay_signature }),
      modal: { ondismiss: () => reject(new Error(NOT_PAID)) },
    }).open();
  });
}
