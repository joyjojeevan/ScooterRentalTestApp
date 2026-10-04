import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api';
import { ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { dateTime, humanize, money } from '../../format';
import { useAction, useLoad } from '../../hooks';
import type { Booking, BookingStatus } from '../../types';

const STATUSES: (BookingStatus | '')[] = ['', 'PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'];

export default function Bookings() {
  const [status, setStatus] = useState<BookingStatus | ''>('');
  const [search, setSearch] = useState('');
  const list = useLoad(() => api.get<Booking[]>(`/admin/bookings${status ? `?status=${status}` : ''}`), [status]);
  const action = useAction();

  const q = search.trim().toLowerCase();
  const rows = (list.data ?? []).filter(
    (b) =>
      !q ||
      b.reference.toLowerCase().includes(q) ||
      b.customer.fullName.toLowerCase().includes(q) ||
      b.scooter.code.toLowerCase().includes(q),
  );

  // The customer normally ends their own rental; an admin can do it for them (e.g. scooter left at the office).
  const endRental = async (b: Booking) => {
    if (!window.confirm(`End ${b.reference} for ${b.customer.fullName}? The final total is charged to their card.`)) return;
    await action.run(() => api.put(`/bookings/${b.id}/complete`, {}));
    list.reload();
  };

  const cancel = async (b: Booking) => {
    const msg = b.status === 'ACTIVE' ? `Cancel ${b.reference}? The customer is refunded in full.` : `Cancel ${b.reference}?`;
    if (!window.confirm(msg)) return;
    if (await action.run(() => api.post(`/bookings/${b.id}/cancel`))) list.reload();
  };

  return (
    <div className="stack">
      <h1>Bookings</h1>
      <div className="row">
        <select value={status} onChange={(e) => setStatus(e.target.value as BookingStatus | '')} aria-label="Status filter">
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s ? s.toLowerCase() : 'All statuses'}
            </option>
          ))}
        </select>
        <input placeholder="Search reference, customer, scooter" value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>
      <ErrorAlert error={list.error ?? action.error} />
      {list.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Reference</th>
              <th>Customer</th>
              <th>Scooter</th>
              <th>Started / ended</th>
              <th>Status</th>
              <th className="num">Total</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((b) => (
              <tr key={b.id}>
                <td>
                  <Link to={`/bookings/${b.id}`}>{b.reference}</Link>
                </td>
                <td>
                  {b.customer.fullName}
                  <div className="muted small">{b.customer.phone}</div>
                </td>
                <td>
                  {b.scooter.code}
                  <div className="muted small">{b.scooter.model}</div>
                </td>
                <td>
                  {dateTime(b.startTime)}
                  {b.endTime && <div className="muted small">→ {dateTime(b.endTime)}</div>}
                </td>
                <td>
                  <StatusBadge status={b.status} />
                  <div className="muted small">{humanize(b.stage)}</div>
                  {b.stage === 'BALANCE_DUE' && (
                    <div className="badge red">Due {money(b.payment?.balanceDue)}</div>
                  )}
                </td>
                <td className="num">
                  {b.totalCost != null ? money(b.totalCost) : b.payment ? <span className="muted">paid {money(b.payment.amount)}</span> : '—'}
                </td>
                <td className="right">
                  <div className="row" style={{ justifyContent: 'flex-end' }}>
                    {b.stage === 'IN_PROGRESS' && (
                      <button className="small primary" disabled={action.busy} onClick={() => endRental(b)}>
                        End rental
                      </button>
                    )}
                    {(b.stage === 'AWAITING_PAYMENT' || b.stage === 'PAYMENT_FAILED' || b.stage === 'UPCOMING') && (
                      <button className="small danger" disabled={action.busy} onClick={() => cancel(b)}>
                        Cancel
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
