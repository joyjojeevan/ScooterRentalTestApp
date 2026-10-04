import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { ErrorAlert, Loading, ScooterThumb, StatusBadge } from '../components/ui';
import { money } from '../format';
import { useLoad } from '../hooks';
import type { GearItem, Scooter } from '../types';

export default function Home() {
  const [params, setParams] = useSearchParams();
  const availableOnly = params.get('available') === '1';

  const scooters = useLoad(() => api.get<Scooter[]>('/scooters'), []);
  const gear = useLoad(() => api.get<GearItem[]>('/gear'));
  const shown = (scooters.data ?? []).filter((s) => !availableOnly || s.available);

  return (
    <div className="stack">
      <section className="hero">
        <h1>Explore Kandy and the hill country on two wheels</h1>
        <p>
          Well-maintained scooters with helmets, plus tents and camping gear for your trip to Knuckles, Hunnasgiriya or
          Ella. Book online, sign digitally, pick up in town, and pay by the hour and kilometre.
        </p>
        <label className="inline">
          <input
            type="checkbox"
            checked={availableOnly}
            onChange={(e) => setParams(e.target.checked ? { available: '1' } : {})}
          />
          Show only scooters available now
        </label>
      </section>

      <div className="row between">
        <h2>{availableOnly ? 'Available now' : 'Our fleet'}</h2>
        {scooters.data && <span className="muted small">{shown.length} scooters</span>}
      </div>
      <ErrorAlert error={scooters.error} />
      {scooters.loading && <Loading />}
      <div className="grid">
        {shown.map((s) => (
          <article key={s.id} className="card scooter-card">
            <ScooterThumb imageUrl={s.imageUrl} alt={s.model} />
            <div className="row between">
              <h3 style={{ margin: 0 }}>{s.model}</h3>
              {s.available ? <StatusBadge status="AVAILABLE" /> : <span className="badge">Not available now</span>}
            </div>
            <div className="muted small">
              {s.engineCc ? `${s.engineCc} cc · ` : ''}
              {s.code}
            </div>
            <p className="small" style={{ margin: 0 }}>
              {s.description}
            </p>
            <div className="row between" style={{ marginTop: 'auto' }}>
              <div>
                <span className="price">{money(s.hourlyRate)}</span>
                <span className="muted small"> / hour + {money(s.perKmRate)} / km</span>
              </div>
              {s.available ? (
                <Link className="btn primary" to={`/scooters/${s.id}`}>
                  Book
                </Link>
              ) : (
                <button disabled>Book</button>
              )}
            </div>
          </article>
        ))}
      </div>
      {scooters.data && shown.length === 0 && <p className="muted">No scooters are available right now.</p>}

      <h2 style={{ marginTop: 16 }}>Camping & travel gear add-ons</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Category</th>
              <th className="num">Per day</th>
            </tr>
          </thead>
          <tbody>
            {gear.data?.map((g) => (
              <tr key={g.id}>
                <td>
                  {g.name}
                  <div className="muted small">{g.description}</div>
                </td>
                <td>
                  <StatusBadge status={g.category} />
                </td>
                <td className="num">{money(g.dailyRate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
