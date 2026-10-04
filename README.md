# Scooter Rent Kandy

Scooter and camping-gear rental platform for Kandy, Sri Lanka.

```
React (Vite, TypeScript)  :5173   ──/api proxy──▶  Spring Boot 3 (Java 17)  :8080  ──▶  PostgreSQL
```

All external services (payments, email/SMS, GPS trackers) run in **mock/test mode** behind interfaces, so the app is fully usable locally.

## Features

| Area | Customer | Admin |
|---|---|---|
| **Scooters** | Browse fleet, see which scooters are available now | Add/edit scooters, set status (available / maintenance), remove from or restore to the fleet |
| **Booking** | Choose a start time → reserve → sign contract → pay the initial charge → ride → end the rental; cancel before the start | List/filter/search, end a rental on a customer's behalf, cancel |
| **Camping gear** | Add tents, sleeping bags, stoves, helmets etc. to a booking (stock-aware) | Manage gear inventory and rates |
| **Contracts** | Digital rental agreement generated per booking, signed by typing full name | Stored with signer name, IP and timestamp |
| **Payments** | Stripe-shaped flow: payment intent → card confirmed in the browser → webhook. Initial charge at booking, remaining balance at the end | One payment per booking: amount, status, balance due |
| **Billing** | Final invoice when the rental ends: hours, GPS kilometres, gear days | Invoice list with payment status |
| **GPS** | Live map, distance and running cost during the rental; distance is billed | Live fleet map, 6-hour trails, speed-violation alerts (over 60 km/h) |
| **Maintenance** | — | Log service/repair/inspection; auto-schedules a service every 3,000 km |
| **Notifications** | In-app inbox (+ mock email/SMS) for confirmations, reminders | Delivery log, broadcast to all customers |
| **Reports** | — | Dashboard, revenue by day, utilisation, scooter performance, gear usage, CSV export |

### Business rules

- **Open-ended rentals (SDS):** a booking has a start time but no end time. It runs until the customer (or an admin for them) ends it. A scooter with a pending or active booking can't be booked by anyone else; its row is locked while a booking is created.
- **Billing (SDS 4.3):** total = started hours × hourly rate + GPS kilometres × per-km rate + each gear item × daily rate × started days (hours / 24, rounded up). Minimum one hour. Rates are read from the scooter and gear item when the rental ends. No deposit, no other fees.
- **Payments:** the initial charge (1 hour + 1 day of gear) is taken at booking. When the rental ends, the balance is charged to the same card and added to the booking's single payment. If that charge fails, the booking stays ACTIVE with the balance due until it is paid online or retried.
- An unpaid booking holds the scooter for **30 minutes**, then expires.
- **Statuses (SDS):** bookings go PENDING → ACTIVE (paid, contract signed) → COMPLETED, or CANCELLED. Payments are PENDING, SUCCESS, FAILED or REFUNDED.
- **Cancellation:** an unpaid (PENDING) booking can be cancelled any time. A paid (ACTIVE) booking can be cancelled only before its start time and while it has no GPS logs, with a full refund.
- **Daily job (08:00 Asia/Colombo):** a reminder the day before a paid rental starts.

## Running locally

### Prerequisites

- Java 17+, Maven 3.9+
- Node 18+ (tested with 22)
- PostgreSQL 14+ (tested with 16)

### 1. Database

```sql
CREATE DATABASE scooter_rent_kandy;
```

Flyway creates the schema on startup (`backend/src/main/resources/db/migration`).

### 2. Backend

```bash
cd backend
# Set your Postgres credentials (defaults: postgres / postgres)
export DB_USERNAME=postgres DB_PASSWORD=yourpassword     # PowerShell: $env:DB_PASSWORD="yourpassword"
mvn spring-boot:run
```

On first start with an empty database, demo data is seeded:

| Role | Email | Password |
|---|---|---|
| Admin | `admin@scooterrentkandy.lk` | `Admin@12345` |
| Customer | `demo@example.com` | `Demo@12345` |

The seed includes 6 scooters, 8 gear items, past bookings (so reports aren't empty), one active rental whose scooter moves on the GPS map, and one upcoming booking.

> **Maven "PKIX path building failed"?** Antivirus or a corporate proxy is intercepting HTTPS. Point Maven at the Windows certificate store:
> `$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (PowerShell), then run Maven again.

### 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

### Tests

```bash
cd backend && mvn test        # integration tests on in-memory H2 (Postgres mode); no DB needed
cd frontend && npm run build  # typecheck + production build
```

## Configuration

Environment variables (see `backend/src/main/resources/application.yml`):

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/scooter_rent_kandy` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | DB credentials |
| `JWT_SECRET` | dev value | **Must be changed** outside local dev (≥ 32 bytes) |
| `CORS_ORIGINS` | `http://localhost:5173` | Allowed browser origins |
| `SEED_DATA` | `true` | Seed demo data into an empty DB |
| `GPS_SIMULATOR` | `true` | Simulated trackers move rented scooters around Kandy |
| `GPS_DEVICE_API_KEY` | `local-device-key` | Key that real trackers send as `X-Device-Key` |
| `PAYMENTS_WEBHOOK_SECRET` | dev value | Signs/verifies payment webhooks (`Stripe-Signature`). **Must be changed** outside local dev |

## Mock services and swapping in real ones

| Service | Interface | Mock | Test behaviour |
|---|---|---|---|
| Card payments (Stripe) | `payment/PaymentGateway` (PaymentIntent shape) | `MockPaymentGateway` + `MockStripeController` (`/mock-stripe/**`) and `frontend/src/paymentClient.ts` | Stripe test cards: `4242 4242 4242 4242` succeeds, `4000 0000 0000 0002` declined, `4000 0000 0000 9995` insufficient funds, `4000 0000 0000 0341` pays at booking but the balance charge at the end fails. Any future MM/YY and a 3-digit CVC. |
| Distance (Google Maps) | `gps/DistanceService` | `HaversineDistanceService` | Sums straight-line distance between consecutive GPS logs |
| Email / SMS | `notification/OutboundMessageSender` | `MockOutboundMessageSender` | Logged as `[MOCK EMAIL]` / `[MOCK SMS]` |
| GPS trackers | `POST /api/gps/pings` | `MockGpsSimulator` | Random walk within 25 km of Kandy every 30 s |

To go live, add an implementation (e.g. a `StripeGateway` using the Stripe Java SDK, a Google Distance Matrix `DistanceService`, an SMTP + SMS-gateway sender) annotated `@ConditionalOnProperty(name = "app.payments.provider", havingValue = "stripe")` and change the property; for Stripe, replace `paymentClient.ts` with Stripe.js / Elements. Card details never reach the backend: the browser confirms the payment with the provider and the backend learns the result from the signed webhook (`POST /api/webhooks/stripe`).

Tracker payload:

```http
POST /api/gps/pings
X-Device-Key: <GPS_DEVICE_API_KEY>
Content-Type: application/json

{ "scooterCode": "SRK-03", "latitude": 7.2936, "longitude": 80.6413, "speedKmh": 22.5 }
```

## Project layout

```
backend/src/main/java/lk/scooterrentkandy/
  booking/       bookings, pricing, lifecycle, scheduled jobs
  billing/       invoices
  contract/      rental agreement generation & signing
  gear/          camping gear inventory & availability
  gps/           tracking, simulator, speed alerts
  maintenance/   service/repair records, auto-scheduling
  notification/  in-app + outbound messages
  payment/       gateway abstraction, payment ledger
  report/        dashboard, summaries, CSV
  scooter/       fleet
  security/      JWT auth, roles (USER, ADMIN, SUPER_ADMIN)
  user/          accounts, profiles, admin customer management
  config/        properties, demo data seeder
frontend/src/
  pages/         customer pages
  pages/admin/   admin console
  components/    layout, map, invoice, UI bits
```

## Before production

- The contract template (`ContractService.TEMPLATE`) is a starting point. Have it reviewed by a lawyer.
- Set a strong `JWT_SECRET`, real provider credentials, and `SEED_DATA=false`.
- Add a payment webhook for asynchronous confirmation once a real gateway is wired in.
- Upload and verify licence/ID documents (fields exist; file upload does not yet).
