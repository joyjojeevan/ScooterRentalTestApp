import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import { CardPayment } from '../components/CardPayment';
import { ErrorAlert, Loading } from '../components/ui';
import { date, dateTime, money } from '../format';
import { useAction, useLoad } from '../hooks';
import type { Booking, Contract, PaymentIntent } from '../types';

export default function Checkout() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const booking = useLoad(() => api.get<Booking>(`/bookings/${id}`), [id]);
  const contract = useLoad(() => api.get<Contract>(`/bookings/${id}/contract`), [id]);

  const [signedName, setSignedName] = useState('');
  const [agree, setAgree] = useState(false);
  const sign = useAction();

  if (booking.loading || contract.loading) return <Loading />;
  if (!booking.data || !contract.data) return <ErrorAlert error={booking.error ?? contract.error} />;
  const b = booking.data;
  const c = contract.data;

  if (b.status !== 'PENDING') {
    return (
      <div className="card stack">
        <p>
          This booking is <strong>{b.status.toLowerCase()}</strong>.
        </p>
        <Link to={`/bookings/${b.id}`} className="btn">
          View booking
        </Link>
      </div>
    );
  }

  const doSign = async () => {
    const res = await sign.run(() => api.post<Contract>(`/bookings/${id}/contract/sign`, { signedName, agree }));
    if (res) contract.setData(res);
  };

  return (
    <div className="stack">
      <div className="steps">
        <span className="step done">1. Reserve</span>
        <span className={`step ${c.signed ? 'done' : 'current'}`}>2. Sign agreement</span>
        <span className={`step ${c.signed ? 'current' : ''}`}>3. Pay</span>
      </div>
      <div className="grid-2">
        <div className="card stack">
          <h2>Rental agreement</h2>
          <div className="contract" tabIndex={0}>
            {c.termsText}
          </div>
          {c.signed ? (
            <div className="alert success">
              Signed by <strong>{c.signedName}</strong> on {date(c.signedAt)}.
            </div>
          ) : (
            <>
              <label className="inline">
                <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />I have read and
                agree to the rental terms
              </label>
              <label>
                Type your full name to sign ({user?.fullName})
                <input value={signedName} onChange={(e) => setSignedName(e.target.value)} autoComplete="name" />
              </label>
              <ErrorAlert error={sign.error} />
              <button className="primary" disabled={!agree || !signedName || sign.busy} onClick={doSign}>
                Sign agreement
              </button>
            </>
          )}
        </div>

        <div className="stack">
          <div className="card stack">
            <h2>Summary</h2>
            <div>
              <strong>{b.scooter.model}</strong> <span className="muted">({b.scooter.plateNumber})</span>
            </div>
            <div className="muted">From {dateTime(b.startTime)} until you end the rental</div>
            {b.gear.length > 0 && <div className="small">Gear: {b.gear.map((g) => `${g.name} ×${g.quantity}`).join(', ')}</div>}
            <hr />
            <div className="row between">
              <span>Scooter</span>
              <span className="num">
                {money(b.scooter.hourlyRate)}/hour + {money(b.scooter.perKmRate)}/km
              </span>
            </div>
            {b.gear.map((g) => (
              <div key={g.gearItemId} className="row between">
                <span>
                  {g.name} ×{g.quantity}
                </span>
                <span className="num">{money(g.dailyRate * g.quantity)}/day</span>
              </div>
            ))}
            <div className="row between">
              <strong>Pay now (1 hour + 1 day of gear)</strong>
              <strong className="num">{money(b.initialCharge)}</strong>
            </div>
            <p className="muted small">
              When you end the rental, the total for every started hour, the kilometres from the GPS log and each
              started day of gear is calculated, and the balance is charged to the same card.
            </p>
          </div>

          <div className="card stack">
            <h2>Payment</h2>
            {b.stage === 'PAYMENT_FAILED' && b.payment?.failureReason && (
              <div className="alert" role="alert">
                Last attempt failed: {b.payment.failureReason}
              </div>
            )}
            <CardPayment
              amount={b.initialCharge}
              disabled={!c.signed}
              defaultName={user?.fullName}
              createIntent={() => api.post<PaymentIntent>(`/bookings/${id}/payment-intent`)}
              onPaid={() => navigate(`/bookings/${id}?paid=1`)}
            />
            {!c.signed && <p className="muted small">Sign the agreement first.</p>}
          </div>
        </div>
      </div>
    </div>
  );
}
