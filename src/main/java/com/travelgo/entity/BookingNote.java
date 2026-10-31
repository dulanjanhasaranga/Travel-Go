package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records the conversation between staff and customers for booking info requests.
 * Types: INFO_REQUEST (staff asks customer), CUSTOMER_REPLY (customer responds), INTERNAL (staff-only note).
 */
@Entity
@Table(name = "booking_notes")
public class BookingNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 30)
    private String noteType; // INFO_REQUEST, CUSTOMER_REPLY, INTERNAL

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }
    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }
    public String getNoteType() { return noteType; }
    public void setNoteType(String noteType) { this.noteType = noteType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
