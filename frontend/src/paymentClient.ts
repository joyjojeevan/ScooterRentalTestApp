import type { PaymentIntent } from './types';

/**
 * Browser side of the Stripe-shaped payment flow (SDS 4.2, 6.2). Like Stripe.js confirmCardPayment, card details
 * stay in the browser: only a payment-method token is sent to the payment provider, and nothing card-related is
 * ever sent to our API. The provider then tells our backend the outcome through a signed webhook.
 *
 * Mock mode (development, SDS 7.2) maps Stripe's test card numbers to Stripe's test tokens. A production build
 * would replace this module with @stripe/stripe-js and Stripe Elements; nothing else changes.
 */

export interface CardInput {
  number: string;
  expiry: string;
  cvc: string;
  name: string;
}

export interface ConfirmResult {
  status: 'succeeded' | 'requires_payment_method';
  error?: string;
}

/** Stripe test cards and the test payment methods they stand for. */
export const TEST_CARDS: { number: string; token: string; label: string }[] = [
  { number: '4242 4242 4242 4242', token: 'pm_card_visa', label: 'succeeds' },
  { number: '4000 0000 0000 0002', token: 'pm_card_chargeDeclined', label: 'declined' },
  { number: '4000 0000 0000 9995', token: 'pm_card_chargeDeclinedInsufficientFunds', label: 'insufficient funds' },
  { number: '4000 0000 0000 0341', token: 'pm_card_chargeCustomerFail', label: 'pays now, balance at the end fails' },
];

function tokenize(card: CardInput): { paymentMethod: string; last4: string } {
  const digits = card.number.replace(/\D/g, '');
  const match = TEST_CARDS.find((c) => c.number.replace(/\D/g, '') === digits);
  if (!match) throw new Error('Test mode: use one of the test card numbers shown.');
  const m = /^(\d{2})\s*\/\s*(\d{2})$/.exec(card.expiry.trim());
  const month = m ? Number(m[1]) : 0;
  if (!m || month < 1 || month > 12) throw new Error('Enter the expiry as MM/YY.');
  const now = new Date();
  const expiryEnd = new Date(2000 + Number(m[2]), month, 1);
  if (expiryEnd <= now) throw new Error('This card has expired.');
  if (!/^\d{3,4}$/.test(card.cvc.trim())) throw new Error('Enter the 3 or 4 digit security code.');
  if (!card.name.trim()) throw new Error('Enter the name on the card.');
  return { paymentMethod: match.token, last4: digits.slice(-4) };
}

/** Confirms a payment intent with the provider, as Stripe.js does. */
export async function confirmCardPayment(intent: PaymentIntent, card: CardInput): Promise<ConfirmResult> {
  if (intent.provider !== 'mock') {
    throw new Error(`Payment provider "${intent.provider}" is not available in this build.`);
  }
  const { paymentMethod, last4 } = tokenize(card);
  const res = await fetch(`/mock-stripe/v1/payment_intents/${encodeURIComponent(intent.intentId)}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientSecret: intent.clientSecret, paymentMethod, cardLast4: last4 }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data?.message ?? `Payment failed (${res.status})`);
  return data as ConfirmResult;
}
