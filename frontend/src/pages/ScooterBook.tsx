import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import { ErrorAlert, Loading, ScooterThumb } from '../components/ui';
import { dateTimeInput, money, nextQuarterHour } from '../format';
import { useAction, useLoad } from '../hooks';
import type { Booking, GearItem, Quote, Scooter } from '../types';

export default function ScooterBook() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { user } = useAuth();

  // Rentals are open-ended (SDS 4.2): choose when it starts; it ends when you end it.
  const [start, setStart] = useState(params.get('start') ?? nextQuarterHour());
  const [qty, setQty] = useState<Record<string, number>>({});
  const [pickup, setPickup] = useState('Kandy office, Dalada Veediya');
  const [notes, setNotes] = useState('');
  const [quote, setQuote] = useState<Quote | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const create = useAction();

  const scooter = useLoad(() => api.get<Scooter>(`/scooters/${id}`), [id]);
  const gear = useLoad(() => api.get<GearItem[]>('/gear'), []);
  const startValid = start >= dateTimeInput(new Date(Date.now() - 60_000));

  const selection = useMemo(
    () => Object.entries(qty).filter(([, q]) => q > 0).map(([gid, q]) => ({ gearItemId: gid, quantity: q })),
    [qty],
  );

  useEffect(() => {
    let cancelled = false;
    api
      .post<Quote>('/bookings/quote', { scooterId: id, gear: selection })
      .then((q) => {
        if (!cancelled) {
          setQuote(q);
          setQuoteError(null);
        }
      })
      .catch((e) => !cancelled && setQuoteError(e.message));
    return () => {
      cancelled = true;
    };
  }, [id, selection]);

  const submit = async () => {
    if (!user) {
      navigate('/login', { state: { from: `/scooters/${id}?start=${encodeURIComponent(start)}` } });
      return;
    }
    const booking = await create.run(() =>
      api.post<Booking>('/bookings', {
        scooterId: id,
        startTime: start,
        gear: selection,
        pickupLocation: pickup,
        notes,
      }),
    );
    if (booking) navigate(`/bookings/${booking.id}/checkout`);
  };

  if (scooter.loading) return <Loading />;
  if (!scooter.data) return <ErrorAlert error={scooter.error ?? 'Scooter not found'} />;
  const s = scooter.data;

  return (
    <div className="grid-2">
      <div className="stack">
        <div className="card stack">
          <ScooterThumb imageUrl={s.imageUrl} alt={s.model} />
          <div>
            <h1>{s.model}</h1>
            <div className="muted">
              {s.code} · {s.plateNumber} {s.engineCc ? `· ${s.engineCc} cc` : ''}
            </div>
          </div>
          <p style={{ margin: 0 }}>{s.description}</p>
          <div className="row">
            <span className="price">{money(s.hourlyRate)}</span>
            <span className="muted">per hour + {money(s.perKmRate)} per km</span>
          </div>
          {!s.available && <div className="alert">This scooter is not available right now.</div>}
        </div>

        <div className="card stack">
          <h2>Start & pickup</h2>
          <label>
            Start of rental
            <input type="datetime-local" min={dateTimeInput(new Date())} value={start} onChange={(e) => setStart(e.target.value)} />
          </label>
          <p className="muted small" style={{ margin: 0 }}>
            No return date needed: the rental runs until you end it in the app.
          </p>
          <label>
            Pickup location
            <select value={pickup} onChange={(e) => setPickup(e.target.value)}>
              <option>Kandy office, Dalada Veediya</option>
              <option>Kandy Railway Station</option>
              <option>Hotel delivery in Kandy city (arrange by phone)</option>
            </select>
          </label>
          <label>
            Notes for us (optional)
            <textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Arrival time, questions…" />
          </label>
        </div>
      </div>

      <div className="stack">
        <div className="card stack">
          <h2>Add camping & travel gear</h2>
          {gear.data?.map((g) => {
            const max = g.available ?? g.totalQuantity;
            return (
              <div key={g.id} className="row between">
                <div>
                  <div>{g.name}</div>
                  <div className="muted small">
                    {money(g.dailyRate)}/day · {max} available
                  </div>
                </div>
                <input
                  type="number"
                  aria-label={`Quantity of ${g.name}`}
                  min={0}
                  max={max}
                  value={qty[g.id] ?? 0}
                  style={{ width: 72 }}
                  onChange={(e) => setQty({ ...qty, [g.id]: Math.max(0, Math.min(max, Number(e.target.value))) })}
                />
              </div>
            );
          })}
        </div>

        <div className="card stack">
          <h2>Price</h2>
          <ErrorAlert error={quoteError} />
          {quote && (
            <table>
              <tbody>
                <tr>
                  <td>Scooter, per started hour</td>
                  <td className="num">{money(quote.hourlyRate)}</td>
                </tr>
                <tr>
                  <td>Distance, per km (from GPS)</td>
                  <td className="num">{money(quote.perKmRate)}</td>
                </tr>
                {quote.gear.map((g) => (
                  <tr key={g.gearItemId}>
                    <td>
                      {g.name} × {g.quantity}, per started day
                    </td>
                    <td className="num">{money(g.lineTotal)}</td>
                  </tr>
                ))}
                <tr>
                  <td>
                    <strong>Pay now (1 hour + 1 day of gear)</strong>
                  </td>
                  <td className="num">
                    <strong>{money(quote.initialCharge)}</strong>
                  </td>
                </tr>
              </tbody>
            </table>
          )}
          <p className="muted small" style={{ margin: 0 }}>
            When you end the rental: hours × hourly rate + km × per-km rate + gear for each started day. The balance
            after today's payment is charged to the same card.
          </p>
          <ErrorAlert error={create.error} />
          <button className="primary" disabled={!quote || !startValid || !s.available || create.busy} onClick={submit}>
            {user ? (create.busy ? 'Reserving…' : 'Reserve & continue') : 'Log in to book'}
          </button>
          <p className="muted small" style={{ margin: 0 }}>
            Next: review and sign the rental agreement, then pay. Unpaid reservations are released after 30 minutes.
          </p>
        </div>
      </div>
    </div>
  );
}
