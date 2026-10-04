import { Link } from 'react-router-dom';
import { api } from '../api';
import { Empty, ErrorAlert, Loading, StatusBadge } from '../components/ui';
import { dateTime, humanize, money } from '../format';
import { useLoad } from '../hooks';
import type { Booking } from '../types';

export default function MyBookings() {
  const { data, error, loading } = useLoad(() => api.get<Booking[]>('/bookings'));

  return (
    <div className="stack">
      <h1>My bookings</h1>
      <ErrorAlert error={error} />
      {loading && <Loading />}
      {data?.length === 0 && (
        <Empty>
          No bookings yet. <Link to="/">Find a scooter</Link>.
        </Empty>
      )}
      {data && data.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Reference</th>
                <th>Scooter</th>
                <th>Started</th>
                <th>Status</th>
                <th className="num">Total</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.map((b) => (
                <tr key={b.id}>
                  <td>{b.reference}</td>
                  <td>
                    {b.scooter.model}
                    <div className="muted small">{b.scooter.plateNumber}</div>
                  </td>
                  <td>{dateTime(b.startTime)}</td>
                  <td>
                    <StatusBadge status={b.status} />
                    <div className="muted small">{humanize(b.stage)}</div>
                  </td>
                  <td className="num">
                    {b.totalCost != null ? money(b.totalCost) : b.payment ? <span className="muted">paid {money(b.payment.amount)}</span> : '—'}
                  </td>
                  <td className="right">
                    {b.status === 'PENDING' ? (
                      <Link className="btn small primary" to={`/bookings/${b.id}/checkout`}>
                        Complete
                      </Link>
                    ) : (
                      <Link className="btn small" to={`/bookings/${b.id}`}>
                        View
                      </Link>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
