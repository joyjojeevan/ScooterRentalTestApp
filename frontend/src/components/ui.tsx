import { ReactNode } from 'react';
import { humanize } from '../format';

const STATUS_TONE: Record<string, string> = {
  AVAILABLE: 'green',
  PAID: 'green',
  SUCCESS: 'green',
  DONE: 'green',
  SENT: 'green',
  ACTIVE: 'blue',
  RENTED: 'blue',
  IN_PROGRESS: 'blue',
  PENDING: 'amber',
  MAINTENANCE: 'amber',
  SCHEDULED: 'amber',
  ISSUED: 'amber',
  CANCELLED: 'red',
  FAILED: 'red',
  REFUNDED: 'red',
  VOID: 'red',
};

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${STATUS_TONE[status] ?? ''}`}>{humanize(status)}</span>;
}

export function ErrorAlert({ error }: { error: string | null | undefined }) {
  return error ? <div className="alert" role="alert">{error}</div> : null;
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return <p className="muted">{what}…</p>;
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {hint && <div className="muted small">{hint}</div>}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="muted">{children}</p>;
}

export function ScooterThumb({ imageUrl, alt }: { imageUrl: string | null; alt: string }) {
  return (
    <div className="scooter-thumb" aria-hidden={!imageUrl}>
      {imageUrl ? <img src={imageUrl} alt={alt} /> : '🛵'}
    </div>
  );
}
