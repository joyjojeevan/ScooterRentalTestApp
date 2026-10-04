import { useState } from 'react';
import { api, download } from '../../api';
import { ErrorAlert, Loading, Stat } from '../../components/ui';
import { addDays, date, money, today } from '../../format';
import { useAction, useLoad } from '../../hooks';
import type { ReportSummary } from '../../types';

export default function Reports() {
  const [to, setTo] = useState(today());
  const [from, setFrom] = useState(addDays(today(), -29));
  const report = useLoad(() => api.get<ReportSummary>(`/admin/reports/summary?from=${from}&to=${to}`), [from, to]);
  const exporter = useAction();
  const r = report.data;

  return (
    <div className="stack">
      <div className="row between">
        <h1>Reports</h1>
        <div className="row">
          <label>
            From
            <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label>
            To
            <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
          </label>
          <button
            style={{ alignSelf: 'end' }}
            disabled={exporter.busy}
            onClick={() => exporter.run(() => download(`/admin/reports/bookings.csv?from=${from}&to=${to}`, `bookings-${from}-${to}.csv`))}
          >
            Export bookings CSV
          </button>
        </div>
      </div>
      <ErrorAlert error={report.error ?? exporter.error} />
      {report.loading && <Loading />}
      {r && (
        <>
          <div className="stats">
            <Stat label="Revenue" value={money(r.revenue)} hint="Payments collected" />
            <Stat label="Refunds" value={money(r.refunds)} hint="Cancelled before the start" />
            <Stat label="Balance outstanding" value={money(r.outstandingBalance)} hint="Ended rentals not yet paid in full" />
            <Stat label="Maintenance cost" value={money(r.maintenanceCost)} />
            <Stat label="Fleet utilisation" value={`${r.fleetUtilizationPct}%`} hint="Rented scooter-hours" />
            <Stat label="Bookings created" value={r.bookingsCreated} />
            <Stat label="New customers" value={r.newCustomers} />
          </div>

          <div className="card stack">
            <h2>Daily revenue (rental + fees)</h2>
            <BarChart data={r.revenueByDay} />
            <details>
              <summary className="small muted">Show as table</summary>
              <table>
                <thead>
                  <tr>
                    <th>Date</th>
                    <th className="num">Revenue</th>
                  </tr>
                </thead>
                <tbody>
                  {r.revenueByDay
                    .filter((d) => d.amount > 0)
                    .map((d) => (
                      <tr key={d.date}>
                        <td>{date(d.date)}</td>
                        <td className="num">{money(d.amount)}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </details>
          </div>

          <div className="grid-2">
            <div className="card stack">
              <h2>Scooter performance</h2>
              <table>
                <thead>
                  <tr>
                    <th>Scooter</th>
                    <th className="num">Bookings</th>
                    <th className="num">Hours out</th>
                    <th className="num">Revenue</th>
                  </tr>
                </thead>
                <tbody>
                  {r.scooters.map((s) => (
                    <tr key={s.scooterId}>
                      <td>
                        {s.code} <span className="muted small">{s.model}</span>
                      </td>
                      <td className="num">{s.bookings}</td>
                      <td className="num">{s.rentedHours}</td>
                      <td className="num">{money(s.revenue)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="card stack">
              <h2>Bookings by status</h2>
              <table>
                <tbody>
                  {Object.entries(r.bookingsByStatus).map(([s, n]) => (
                    <tr key={s}>
                      <td>{s.replace('_', ' ').toLowerCase()}</td>
                      <td className="num">{n}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <h2>Gear rented (units)</h2>
              {r.gear.length === 0 && <p className="muted">No gear rented in this period.</p>}
              <table>
                <tbody>
                  {r.gear.map((g) => (
                    <tr key={g.name}>
                      <td>{g.name}</td>
                      <td className="num">{g.units}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** Single-series bar chart: thin bars, rounded tops, recessive axis, per-bar hover tooltip. */
function BarChart({ data }: { data: { date: string; amount: number }[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const width = 760;
  const height = 220;
  const pad = { top: 16, right: 8, bottom: 24, left: 8 };
  const max = Math.max(1, ...data.map((d) => d.amount));
  const slot = (width - pad.left - pad.right) / Math.max(1, data.length);
  const barW = Math.max(2, Math.min(18, slot - 2)); // 2px surface gap between bars
  const plotH = height - pad.top - pad.bottom;
  const labelEvery = Math.ceil(data.length / 8);
  const h = hover != null ? data[hover] : null;

  return (
    <div style={{ position: 'relative' }}>
      <svg viewBox={`0 0 ${width} ${height}`} width="100%" role="img" aria-label="Daily revenue bar chart">
        <line className="chart-axis" x1={pad.left} x2={width - pad.right} y1={height - pad.bottom} y2={height - pad.bottom} />
        {data.map((d, i) => {
          const bh = (d.amount / max) * plotH;
          const x = pad.left + i * slot + (slot - barW) / 2;
          const y = height - pad.bottom - bh;
          const r = Math.min(4, barW / 2, bh);
          return (
            <g key={d.date} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              {/* Hit target spans the whole slot, larger than the bar itself. */}
              <rect x={pad.left + i * slot} y={pad.top} width={slot} height={plotH} fill="transparent" />
              {bh > 0 && (
                <path
                  className="chart-bar"
                  opacity={hover == null || hover === i ? 1 : 0.55}
                  d={`M${x},${y + bh} V${y + r} Q${x},${y} ${x + r},${y} H${x + barW - r} Q${x + barW},${y} ${x + barW},${y + r} V${y + bh} Z`}
                />
              )}
              {i % labelEvery === 0 && (
                <text className="chart-label" x={x + barW / 2} y={height - 8} textAnchor="middle">
                  {d.date.slice(5)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      {h && (
        <div
          className="card small"
          style={{
            position: 'absolute',
            top: 0,
            left: `${Math.min(80, (hover! / data.length) * 100)}%`,
            padding: '6px 10px',
            pointerEvents: 'none',
          }}
        >
          <div className="muted">{date(h.date)}</div>
          <strong>{money(h.amount)}</strong>
        </div>
      )}
    </div>
  );
}
