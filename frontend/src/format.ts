const lkr = new Intl.NumberFormat('en-LK', { style: 'currency', currency: 'LKR', maximumFractionDigits: 2 });

export const money = (n: number | null | undefined) => (n == null ? '—' : lkr.format(n));

export const date = (iso: string | null | undefined) =>
  iso ? new Date(iso.length === 10 ? iso + 'T00:00:00' : iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '—';

export const dateTime = (iso: string | null | undefined) =>
  iso
    ? new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
    : '—';

/** yyyy-mm-dd in local time. */
export const isoDate = (d: Date) => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

export const addDays = (iso: string, days: number) => {
  const d = new Date(iso + 'T00:00:00');
  d.setDate(d.getDate() + days);
  return isoDate(d);
};

export const today = () => isoDate(new Date());

export const humanize = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ');

/** Value for an <input type="datetime-local">: yyyy-mm-ddThh:mm in local time. */
export const dateTimeInput = (d: Date) => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${isoDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/** Now, rounded up to the next 15 minutes, as a datetime-local value. */
export const nextQuarterHour = () => {
  const d = new Date();
  d.setSeconds(0, 0);
  d.setMinutes(Math.ceil((d.getMinutes() + 1) / 15) * 15);
  return dateTimeInput(d);
};
