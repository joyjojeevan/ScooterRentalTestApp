// SDS 3.2 UserRole. SUPER_ADMIN has every ADMIN right.
export type Role = 'USER' | 'ADMIN' | 'SUPER_ADMIN';

export const isAdmin = (role: Role | undefined) => role === 'ADMIN' || role === 'SUPER_ADMIN';

// Entity ids are UUIDs, sent as strings (SDS 2.2).
export interface User {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  role: Role;
  idDocumentNumber: string | null;
  drivingLicenseNo: string | null;
  country: string | null;
  active: boolean;
  createdAt: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export type ScooterStatus = 'AVAILABLE' | 'RENTED' | 'MAINTENANCE';

export interface Scooter {
  id: string;
  code: string;
  model: string;
  plateNumber: string;
  engineCc: number | null;
  status: ScooterStatus;
  /** SDS 2.2: LKR per hour and per km. */
  hourlyRate: number;
  perKmRate: number;
  /** SDS 2.2: cumulative km (decimal). */
  totalMileage: number;
  latitude: number | null;
  longitude: number | null;
  imageUrl: string | null;
  description: string | null;
  /** Soft-deleted: removed from the fleet (SDS 8.3). */
  deleted: boolean;
  /** Bookable right now (not rented, in maintenance or held by another booking). */
  available: boolean;
}

export type GearCategory = 'CAMPING' | 'SAFETY' | 'LUGGAGE' | 'ACCESSORY';

export interface GearItem {
  id: string;
  name: string;
  category: GearCategory;
  description: string | null;
  dailyRate: number;
  totalQuantity: number;
  available: number | null;
  active: boolean;
}

/** Rates and the initial charge (1 hour + 1 gear day); the total is known when the rental ends. */
export interface Quote {
  scooterId: string;
  currency: string;
  hourlyRate: number;
  perKmRate: number;
  gear: { gearItemId: string; name: string; quantity: number; dailyRate: number; days: number; lineTotal: number }[];
  gearPerDay: number;
  initialCharge: number;
}

// SDS 3.2. Open-ended rentals: ACTIVE from successful payment until the customer ends the rental.
export type BookingStatus = 'PENDING' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';

/** Display stage derived on the server from status, payment and times. */
export type BookingStage =
  | 'AWAITING_PAYMENT'
  | 'PAYMENT_FAILED'
  | 'UPCOMING'
  | 'IN_PROGRESS'
  | 'BALANCE_DUE'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REFUNDED';

export type PaymentStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'REFUNDED';

export interface Booking {
  id: string;
  reference: string;
  status: BookingStatus;
  stage: BookingStage;
  customer: { id: string; fullName: string; email: string; phone: string | null };
  scooter: {
    id: string;
    code: string;
    model: string;
    plateNumber: string;
    imageUrl: string | null;
    hourlyRate: number;
    perKmRate: number;
  };
  startTime: string;
  endTime: string | null;
  billableHours: number | null;
  distanceKm: number | null;
  totalCost: number | null;
  /** Balance due only: frozen settlement (endTime and totalCost stay null until COMPLETED, SDS 2.2). */
  endedAt: string | null;
  pendingDistanceKm: number | null;
  pendingTotalCost: number | null;
  initialCharge: number;
  gear: { gearItemId: string; name: string; quantity: number; dailyRate: number }[];
  payment: {
    status: PaymentStatus;
    amount: number;
    balanceDue: number | null;
    cardLast4: string | null;
    failureReason: string | null;
    paidAt: string | null;
  } | null;
  pickupLocation: string;
  /** SDS 2.2: GPS position where the rental ended; null until it completes. */
  dropLocation: string | null;
  notes: string | null;
  contractSigned: boolean;
  cancelledAt: string | null;
  createdAt: string;
}

export interface Contract {
  id: string;
  contractNumber: string;
  termsVersion: string;
  termsText: string;
  signed: boolean;
  signedName: string | null;
  signedAt: string | null;
}

/** One payment per booking (SDS 2.3). */
export interface Payment {
  id: string;
  bookingId: string;
  bookingReference: string | null;
  customerName: string | null;
  amount: number;
  method: 'CARD' | 'ONLINE';
  status: PaymentStatus;
  provider: string;
  stripePaymentId: string | null;
  cardLast4: string | null;
  balanceDue: number | null;
  failureReason: string | null;
  paidAt: string | null;
  createdAt: string;
}

/** What the browser needs to confirm a payment with the provider (Stripe PaymentIntent shape). */
export interface PaymentIntent {
  paymentId: string;
  bookingId: string;
  purpose: 'INITIAL' | 'BALANCE';
  intentId: string;
  clientSecret: string;
  amount: number;
  currency: string;
  provider: string;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  bookingId: string;
  bookingReference: string;
  customerName: string;
  customerEmail: string;
  /** FINAL is issued when a rental ends; RENTAL / SETTLEMENT are from the earlier model. */
  kind: 'FINAL' | 'RENTAL' | 'SETTLEMENT';
  /** Earlier invoices only. */
  status: 'ISSUED' | 'PAID' | 'VOID' | null;
  paymentStatus: PaymentStatus | null;
  balanceDue: number | null;
  currency: string;
  subtotal: number;
  tax: number;
  total: number;
  issuedAt: string;
  lines: { description: string; quantity: number; unitPrice: number; amount: number }[];
}

export interface Notification {
  id: string;
  subject: string;
  message: string;
  read: boolean;
  createdAt: string;
}

export interface NotificationLog {
  id: string;
  recipient: string;
  channel: 'IN_APP' | 'EMAIL' | 'SMS';
  subject: string;
  status: 'SENT' | 'FAILED';
  createdAt: string;
}

export interface MaintenanceRecord {
  id: string;
  scooterId: string;
  scooterCode: string;
  scooterModel: string;
  type: 'SERVICE' | 'REPAIR' | 'INSPECTION';
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
  description: string;
  cost: number | null;
  scheduledDate: string | null;
  completedDate: string | null;
  odometerKm: number | null;
  createdAt: string;
}

export interface LivePosition {
  scooterId: string;
  code: string;
  model: string;
  plateNumber: string;
  status: ScooterStatus;
  latitude: number | null;
  longitude: number | null;
  lastSeen: string | null;
  activeBookingReference: string | null;
  riderName: string | null;
  distanceFromBaseKm: number;
}

export interface TrailPoint {
  latitude: number;
  longitude: number;
  speedKmh: number | null;
  recordedAt: string;
}

export interface Tracking {
  position: LivePosition;
  trail: TrailPoint[];
  /** Along the trail; for a booking, the rental so far. */
  distanceKm: number;
}

export interface Dashboard {
  fleetByStatus: Record<ScooterStatus, number>;
  activeRentals: number;
  upcoming: number;
  balanceDue: number;
  pendingPayment: number;
  openMaintenance: number;
  customers: number;
  revenueLast30Days: number;
  currency: string;
}

export interface ReportSummary {
  from: string;
  to: string;
  currency: string;
  revenue: number;
  refunds: number;
  outstandingBalance: number;
  maintenanceCost: number;
  bookingsCreated: number;
  bookingsByStatus: Record<BookingStatus, number>;
  newCustomers: number;
  fleetUtilizationPct: number;
  revenueByDay: { date: string; amount: number }[];
  scooters: { scooterId: string; code: string; model: string; bookings: number; rentedHours: number; revenue: number }[];
  gear: { name: string; units: number }[];
}

/** SDS 4.3 / 5.2: a crossing above 60 km/h. */
export interface SpeedViolation {
  id: string;
  scooterCode: string;
  scooterModel: string;
  plateNumber: string;
  bookingReference: string | null;
  riderName: string | null;
  speedKmh: number;
  latitude: number;
  longitude: number;
  recordedAt: string;
}
