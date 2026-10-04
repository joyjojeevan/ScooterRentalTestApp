package lk.scooterrentkandy.booking;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.scooter.Scooter;
import lk.scooterrentkandy.user.User;
import lombok.Getter;
import lombok.Setter;

/**
 * SDS 2.2 Booking: an open-ended rental. It starts at {@code startTime}; {@code endTime}, {@code distanceKm}
 * and {@code totalCost} are set when the customer ends it. Rates stay on the Scooter and gear items (SDS 2.4).
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scooter_id")
    private Scooter scooter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    /** When the rental (and billing) starts. */
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** SDS 2.2: when the rental ended; NULL until the booking is COMPLETED. */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** Distance from the booking's GPS logs; set when the booking is COMPLETED. */
    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    /** SDS 2.2: final cost; NULL until the booking is COMPLETED. */
    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost;

    /**
     * Frozen settlement, set at the first attempt to end the rental. If the remaining-balance charge fails the
     * booking stays ACTIVE with these kept, so a retry bills exactly the same rental; on completion they move
     * into endTime, distanceKm and totalCost.
     */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "pending_distance_km", precision = 10, scale = 2)
    private BigDecimal pendingDistanceKm;

    @Column(name = "pending_total_cost", precision = 10, scale = 2)
    private BigDecimal pendingTotalCost;

    /** SDS 2.2: address string of the pickup point. */
    @Column(name = "pickup_location", nullable = false, length = 300)
    private String pickupLocation;

    /** SDS 2.2: GPS address of the return point; NULL until the rental ends. */
    @Column(name = "drop_location", length = 300)
    private String dropLocation;

    @Column(length = 2000)
    private String notes;

    /** SDS 2.2: TRUE once the customer has signed the rental contract; required before activation. */
    @Column(name = "contract_signed", nullable = false)
    private boolean contractSigned;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingGear> gear = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void addGear(BookingGear line) {
        line.setBooking(this);
        gear.add(line);
    }
}
