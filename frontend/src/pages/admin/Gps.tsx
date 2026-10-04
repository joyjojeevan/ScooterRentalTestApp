import { useEffect, useState } from 'react';
import { api } from '../../api';
import MapView, { MapMarker } from '../../components/MapView';
import { ErrorAlert, StatusBadge } from '../../components/ui';
import { dateTime } from '../../format';
import type { LivePosition, Tracking } from '../../types';

export default function Gps() {
  const [positions, setPositions] = useState<LivePosition[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [trail, setTrail] = useState<Tracking | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api
        .get<LivePosition[]>('/admin/gps/live')
        .then((p) => {
          if (!cancelled) {
            setPositions(p);
            setError(null);
          }
        })
        .catch((e) => !cancelled && setError(e.message));
    load();
    const t = setInterval(load, 10_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, []);

  useEffect(() => {
    if (selected == null) {
      setTrail(null);
      return;
    }
    api.get<Tracking>(`/admin/gps/scooters/${selected}/trail?hours=6`).then(setTrail).catch((e) => setError(e.message));
  }, [selected, positions]);

  const markers: MapMarker[] = positions
    .filter((p) => p.latitude != null && p.longitude != null)
    .map((p) => ({
      id: p.scooterId,
      lat: p.latitude!,
      lng: p.longitude!,
      kind: p.status,
      popup: (
        <div>
          <strong>{p.code}</strong> {p.model}
          <br />
          {p.plateNumber}
          {p.riderName && (
            <>
              <br />
              Rider: {p.riderName} ({p.activeBookingReference})
            </>
          )}
        </div>
      ),
    }));

  return (
    <div className="stack">
      <div className="row between">
        <h1>Live GPS</h1>
        <span className="muted small">Refreshes every 10 s · simulated trackers in local dev</span>
      </div>
      <ErrorAlert error={error} />
      <MapView markers={markers} trail={trail?.trail.map((t) => [t.latitude, t.longitude] as [number, number])} zoom={12} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Scooter</th>
              <th>Status</th>
              <th>Rider</th>
              <th className="num">From base</th>
              <th>Last ping</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {positions.map((p) => (
              <tr key={p.scooterId}>
                <td>
                  {p.code}
                  <div className="muted small">{p.model}</div>
                </td>
                <td>
                  <StatusBadge status={p.status} />
                </td>
                <td>{p.riderName ? `${p.riderName} · ${p.activeBookingReference}` : '—'}</td>
                <td className="num">{p.distanceFromBaseKm} km</td>
                <td>{dateTime(p.lastSeen)}</td>
                <td className="right">
                  <button className="small" onClick={() => setSelected(selected === p.scooterId ? null : p.scooterId)}>
                    {selected === p.scooterId ? 'Hide trail' : 'Show 6h trail'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
