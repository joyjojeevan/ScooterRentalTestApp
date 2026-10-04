import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import { CardPayment } from '../components/CardPayment';
import InvoiceView from '../components/InvoiceView';
import MapView from '../components/MapView';
import { ErrorAlert, Loading, Stat, StatusBadge } from '../components/ui';
import { dateTime, money } from '../format';
import { useAction, useLoad } from '../hooks';
import { isAdmin } from '../types';
import type { Booking, BookingStage, Contract, Invoice, PaymentIntent, Tracking } from '../types';

const STAGE_TEXT: Record<BookingStage, string> = {
  AWAITING_PAYMENT: 'Awaiting payment',
  PAYMENT_FAILED: 'Payment failed',
  UPCOMING: 'Paid · starts soon',
  IN_PROGRESS: 'Rental in progress',
  BALANCE_DUE: 'Ended · balance due',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  REFUNDED: 'Cancelled · refunded',
};

export default function BookingDetail() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const { user } = useAuth();
  const booking = useLoad(() => api.get<Booking>(`/bookings/${id}`), [id]);
  const invoices = useLoad(() => api.get<Invoice[]>(`/bookings/${id}/invoices`), [id]);
  const [showContract, setShowContract] = useState(false);
  const contract = useLoad(
    () => (showContract ? api.get<Contract>(`/bookings/${id}/contract`) : Promise.resolve(null)),
    [id, showContract],
  );
  const cancel = useAction();
  const end = useAction();

  if (booking.loading) return <Loading />;
  if (!booking.data) return <ErrorAlert error={booking.error} />;
  const b = booking.data;
  const isOwner = user?.id === b.customer.id;
  const canCancel = b.stage === 'AWAITING_PAYMENT' || b.stage === 'PAYMENT_FAILED' || b.stage === 'UPCOMING';
  const canEnd = b.stage === 'IN_PROGRESS' && (isOwner || isAdmin(user?.role));

  const refresh = () => {
    booking.reload();
    invoices.reload();
  };

  const doCancel = async () => {
    const msg =
      b.status === 'ACTIVE' ? 'Cancel this booking? You will receive a full refund.' : 'Cancel this reservation?';
    if (!window.confirm(msg)) return;
    if (await cancel.run(() => api.post<Booking>(`/bookings/${id}/cancel`))) refresh();
  };

  // Ending charges the remaining balance; if that fails the booking stays ACTIVE with the balance due.
  const doEnd = async () => {
    if (!window.confirm('End the rental now? The final total is calculated and the balance charged to your card.')) return;
    await end.run(() => api.put<Booking>(`/bookings/${id}/complete`, {}));
    refresh();
  };

  return (
    <div className="stack">
      {params.get('paid') && b.status === 'ACTIVE' && (
        <div className="alert success">Payment received. Your scooter is booked! A confirmation has been sent to you.</div>
      )}
      <div className="row between">
        <div>
          <h1 style={{ marginBottom: 4 }}>Booking {b.reference}</h1>
          <span className="row">
            <StatusBadge status={b.status} />
            <span className="muted small">{STAGE_TEXT[b.stage]}</span>
          </span>
        </div>
        <div className="row">
          {b.status === 'PENDING' && isOwner && (
            <Link className="btn primary" to={`/bookings/${b.id}/checkout`}>
              {b.stage === 'PAYMENT_FAILED' ? 'Try payment again' : 'Sign & pay'}
            </Link>
          )}
          {canEnd && (
            <button className="primary" onClick={doEnd} disabled={end.busy}>
              {end.busy ? 'Ending rental…' : 'End rental'}
            </button>
          )}
          {canCancel && (
            <button className="danger" onClick={doCancel} disabled={cancel.busy}>
              Cancel booking
            </button>
          )}
        </div>
      </div>
      <ErrorAlert error={cancel.error} />
      {b.stage === 'PAYMENT_FAILED' && b.payment?.failureReason && (
        <div className="alert" role="alert">
          Payment failed: {b.payment.failureReason}
        </div>
      )}
      {b.stage === 'UPCOMING' && (
        <div className="alert info">
          Paid. Your rental starts {dateTime(b.startTime)}. You can cancel for a full refund until then.
        </div>
      )}

      {b.stage === 'BALANCE_DUE' && (
        <BalanceDue booking={b} isOwner={isOwner} retrying={end.busy} onRetry={doEnd} onPaid={refresh} />
      )}
      {b.stage !== 'BALANCE_DUE' && <ErrorAlert error={end.error} />}

      <div className="grid-2">
        <div className="card stack">
          <h2>Details</h2>
          <table>
            <tbody>
              <tr>
                <td className="muted">Scooter</td>
                <td>
                  {b.scooter.model} ({b.scooter.plateNumber})
                </td>
              </tr>
              <tr>
                <td className="muted">Rates</td>
                <td>
                  {money(b.scooter.hourlyRate)}/hour + {money(b.scooter.perKmRate)}/km
                </td>
              </tr>
              <tr>
                <td className="muted">Started</td>
                <td>{dateTime(b.startTime)}</td>
              </tr>
              {(b.endTime ?? b.endedAt) && (
                <tr>
                  <td className="muted">Ended</td>
                  <td>
                    {dateTime(b.endTime ?? b.endedAt)} · {b.billableHours} hour{b.billableHours === 1 ? '' : 's'} ·{' '}
                    {b.distanceKm ?? b.pendingDistanceKm} km
                  </td>
                </tr>
              )}
              <tr>
                <td className="muted">Pickup</td>
                <td>{b.pickupLocation ?? '—'}</td>
              </tr>
              {b.dropLocation && (
                <tr>
                  <td className="muted">Returned at</td>
                  <td>{b.dropLocation}</td>
                </tr>
              )}
              {b.gear.length > 0 && (
                <tr>
                  <td className="muted">Gear</td>
                  <td>{b.gear.map((g) => `${g.name} ×${g.quantity} (${money(g.dailyRate)}/day)`).join(', ')}</td>
                </tr>
              )}
              <tr>
                <td className="muted">Charged at booking</td>
                <td>{money(b.initialCharge)} (1 hour + 1 day of gear)</td>
              </tr>
              {(b.totalCost ?? b.pendingTotalCost) != null && (
                <tr>
                  <td className="muted">Final total</td>
                  <td>
                    <strong>{money(b.totalCost ?? b.pendingTotalCost)}</strong>
                    {b.totalCost == null && <span className="muted small"> (balance due)</span>}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <button className="small" onClick={() => setShowContract(!showContract)}>
            {showContract ? 'Hide' : 'View'} rental agreement {b.contractSigned ? '(signed)' : '(unsigned)'}
          </button>
          {contract.data && <div className="contract">{contract.data.termsText}</div>}
        </div>

        {b.stage === 'IN_PROGRESS' ? <LiveTracking booking={b} /> : <PaymentCard booking={b} />}
      </div>

      {b.stage === 'IN_PROGRESS' && <PaymentCard booking={b} />}
      {invoices.data?.map((inv) => <InvoiceView key={inv.id} invoice={inv} />)}
    </div>
  );
}

function BalanceDue({
  booking: b,
  isOwner,
  retrying,
  onRetry,
  onPaid,
}: {
  booking: Booking;
  isOwner: boolean;
  retrying: boolean;
  onRetry: () => void;
  onPaid: () => void;
}) {
  const due = b.payment?.balanceDue ?? 0;
  return (
    <div className="card stack">
      <h2>Remaining balance due</h2>
      <div className="alert" role="alert">
        The rental ended {dateTime(b.endedAt)} (total {money(b.pendingTotalCost)}), but {money(due)} could not be charged
        {b.payment?.failureReason ? `: ${b.payment.failureReason}` : ''}. The rental stays open until it is paid.
      </div>
      <div className="row">
        <button onClick={onRetry} disabled={retrying}>
          {retrying ? 'Retrying…' : 'Retry saved card'}
        </button>
      </div>
      {isOwner && (
        <CardPayment
          amount={due}
          createIntent={() => api.post<PaymentIntent>(`/bookings/${b.id}/balance-intent`)}
          onPaid={onPaid}
        />
      )}
    </div>
  );
}

function PaymentCard({ booking: b }: { booking: Booking }) {
  const p = b.payment;
  return (
    <div className="card stack">
      <h2>Payment</h2>
      {!p && <p className="muted">No payment yet.</p>}
      {p && (
        <div className="row between">
          <div>
            <div>
              {p.status === 'REFUNDED' ? 'Refunded' : 'Charged'}
              {p.cardLast4 && <span className="muted small"> · card ****{p.cardLast4}</span>}
            </div>
            <div className="muted small">
              {p.paidAt ? dateTime(p.paidAt) : 'Not completed'}
              {p.failureReason && ` · ${p.failureReason}`}
            </div>
            {p.balanceDue != null && <div className="small">Balance due: {money(p.balanceDue)}</div>}
          </div>
          <div className="right">
            <div className="num">{money(p.amount)}</div>
            <StatusBadge status={p.status} />
          </div>
        </div>
      )}
    </div>
  );
}

function LiveTracking({ booking: b }: { booking: Booking }) {
  const [tracking, setTracking] = useState<Tracking | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api
        .get<Tracking>(`/bookings/${b.id}/tracking`)
        .then((t) => !cancelled && setTracking(t))
        .catch((e) => !cancelled && setError(e.message));
    load();
    const timer = setInterval(load, 10_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [b.id]);

  // Running estimate with the billing formula: started hours, GPS km, gear per started day.
  const hours = Math.max(1, Math.ceil((Date.now() - new Date(b.startTime).getTime()) / 3_600_000));
  const km = tracking?.distanceKm ?? 0;
  const gearDays = Math.max(1, Math.ceil(hours / 24));
  const gearCost = b.gear.reduce((sum, g) => sum + g.dailyRate * g.quantity * gearDays, 0);
  const estimate = hours * b.scooter.hourlyRate + km * b.scooter.perKmRate + gearCost;
  const last = tracking?.trail[tracking.trail.length - 1];
  const p = tracking?.position;

  return (
    <div className="card stack">
      <div className="row between">
        <h2 style={{ margin: 0 }}>Your scooter</h2>
        <span className="muted small">Last update {dateTime(p?.lastSeen)}</span>
      </div>
      <ErrorAlert error={error} />
      <div className="stats" aria-live="polite">
        <Stat label="Time so far" value={`${hours} h`} hint="Each started hour" />
        <Stat label="Distance (GPS)" value={`${km.toFixed(2)} km`} />
        <Stat label="Speed" value={last?.speedKmh != null ? `${Math.round(last.speedKmh)} km/h` : '—'} />
        <Stat label="Estimated total" value={money(estimate)} hint="Final total when you end the rental" />
      </div>
      {p?.latitude != null && p.longitude != null && (
        <MapView
          center={[p.latitude, p.longitude]}
          zoom={14}
          markers={[{ id: p.scooterId, lat: p.latitude, lng: p.longitude, kind: 'RENTED', popup: `${p.model} ${p.plateNumber}` }]}
          trail={tracking?.trail.map((t) => [t.latitude, t.longitude] as [number, number])}
        />
      )}
      <p className="muted small" style={{ margin: 0 }}>
        GPS logs your route for distance billing and helps us find you if you need roadside help. In an emergency,
        call our office.
      </p>
    </div>
  );
}
