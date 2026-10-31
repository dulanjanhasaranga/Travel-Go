package com.travelgo.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Entity @Table(name="cancellation_requests")
public class CancellationRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) private Booking booking;
    @ManyToOne(fetch=FetchType.LAZY) private TravelPolicy policy;
    @Column(nullable=false,length=30) private String status="REQUESTED";
    @Column(nullable=false,length=2000) private String reason;
    @Column(length=4000) private String staffResponse;
    @ManyToOne(fetch=FetchType.LAZY) private User quotedBy;
    @Column(length=64) private String paymentFingerprint;
    @Column(nullable=false) private LocalDateTime createdAt;
    private LocalDateTime quotedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;
    @OneToMany(mappedBy="request",cascade=CascadeType.ALL,orphanRemoval=true) private List<CancellationRefundLine> lines=new ArrayList<>();
    public Long getId(){return id;} public Long getVersion(){return version;}
    public Booking getBooking(){return booking;}public void setBooking(Booking v){booking=v;}
    public TravelPolicy getPolicy(){return policy;}public void setPolicy(TravelPolicy v){policy=v;}
    public String getStatus(){return status;}public void setStatus(String v){status=v;}
    public String getReason(){return reason;}public void setReason(String v){reason=v;}
    public String getStaffResponse(){return staffResponse;}public void setStaffResponse(String v){staffResponse=v;}
    public User getQuotedBy(){return quotedBy;}public void setQuotedBy(User v){quotedBy=v;}
    public String getPaymentFingerprint(){return paymentFingerprint;}public void setPaymentFingerprint(String v){paymentFingerprint=v;}
    public LocalDateTime getCreatedAt(){return createdAt;}public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getQuotedAt(){return quotedAt;}public void setQuotedAt(LocalDateTime v){quotedAt=v;}
    public LocalDateTime getExpiresAt(){return expiresAt;}public void setExpiresAt(LocalDateTime v){expiresAt=v;}
    public LocalDateTime getAcceptedAt(){return acceptedAt;}public void setAcceptedAt(LocalDateTime v){acceptedAt=v;}
    public List<CancellationRefundLine> getLines(){return lines;}
    public BigDecimal getRefundTotal(){return lines.stream().map(CancellationRefundLine::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);}
}
