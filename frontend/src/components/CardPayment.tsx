import { useState } from 'react';
import { money } from '../format';
import { useAction } from '../hooks';
import { confirmCardPayment, TEST_CARDS } from '../paymentClient';
import type { PaymentIntent } from '../types';
import { ErrorAlert } from './ui';

/**
 * Card entry for a payment intent. The card is confirmed with the payment provider in the browser
 * (paymentClient); our API only creates the intent and later hears the outcome from the provider's webhook.
 */
export function CardPayment({
  amount,
  createIntent,
  onPaid,
  disabled,
  defaultName,
}: {
  amount: number;
  createIntent: () => Promise<PaymentIntent>;
  onPaid: () => void;
  disabled?: boolean;
  defaultName?: string;
}) {
  const [card, setCard] = useState({ number: '4242 4242 4242 4242', expiry: '12/30', cvc: '123', name: '' });
  const [declined, setDeclined] = useState<string | null>(null);
  const pay = useAction();

  const submit = async () => {
    setDeclined(null);
    const result = await pay.run(async () => {
      const intent = await createIntent();
      return confirmCardPayment(intent, { ...card, name: card.name || defaultName || '' });
    });
    if (!result) return;
    if (result.status === 'succeeded') onPaid();
    else setDeclined(result.error ?? 'The payment was not completed.');
  };

  return (
    <div className="stack">
      <div className="alert info small">
        Test mode. Card details stay in your browser; only a payment token goes to the payment provider. Test cards:{' '}
        {TEST_CARDS.map((c, i) => (
          <span key={c.number}>
            {i > 0 && ', '}
            <code>{c.number}</code> {c.label}
          </span>
        ))}
        .
      </div>
      <label>
        Card number
        <input
          inputMode="numeric"
          autoComplete="cc-number"
          value={card.number}
          onChange={(e) => setCard({ ...card, number: e.target.value })}
        />
      </label>
      <div className="form-grid">
        <label>
          Expiry (MM/YY)
          <input autoComplete="cc-exp" value={card.expiry} onChange={(e) => setCard({ ...card, expiry: e.target.value })} />
        </label>
        <label>
          CVC
          <input inputMode="numeric" autoComplete="cc-csc" value={card.cvc} onChange={(e) => setCard({ ...card, cvc: e.target.value })} />
        </label>
      </div>
      <label>
        Name on card
        <input
          autoComplete="cc-name"
          placeholder={defaultName}
          value={card.name}
          onChange={(e) => setCard({ ...card, name: e.target.value })}
        />
      </label>
      {declined && (
        <div className="alert" role="alert">
          Payment failed: {declined} Try another card.
        </div>
      )}
      <ErrorAlert error={pay.error} />
      <button className="primary" disabled={disabled || pay.busy} onClick={submit}>
        {pay.busy ? 'Processing…' : `Pay ${money(amount)}`}
      </button>
    </div>
  );
}
