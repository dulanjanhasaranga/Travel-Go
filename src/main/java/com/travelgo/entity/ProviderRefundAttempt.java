package com.travelgo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** One durable send claim per refund. A lost response never authorizes another refund POST. */
@Entity @Table(name="provider_refund_attempts",uniqueConstraints=@UniqueConstraint(name="uq_provider_refund_evidence",columnNames={"provider","payment_mode","deployment_reference","provider_refund_reference"}))
public class ProviderRefundAttempt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="refund_id",nullable=false,unique=true) private Refund refund;
    @Column(nullable=false,length=100,unique=true) private String operationReference;
    @Column(nullable=false,length=20) private String provider="PAYHERE";
    @Column(nullable=false,length=10) private String paymentMode;
    @Column(nullable=false,length=100) private String deploymentReference;
    @Column(nullable=false,length=100) private String orderReference;
    @Column(nullable=false,length=100) private String paymentReference;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
    @Column(nullable=false,length=10) private String currency="USD";
    @Column(nullable=false,length=30) private String status="SENDING";
    @Column(length=100) private String providerRefundReference;
    @Column(length=1000) private String reviewNote;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="requested_by_id",nullable=false) private User requestedBy;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="reviewed_by_id") private User reviewedBy;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    public Long getId(){return id;}
    public Refund getRefund(){return refund;}public void setRefund(Refund v){refund=v;}
    public String getOperationReference(){return operationReference;}public void setOperationReference(String v){operationReference=v;}
    public String getProvider(){return provider;}public void setProvider(String v){provider=v;}
    public String getPaymentMode(){return paymentMode;}public void setPaymentMode(String v){paymentMode=v;}
    public String getDeploymentReference(){return deploymentReference;}public void setDeploymentReference(String v){deploymentReference=v;}
    public String getOrderReference(){return orderReference;}public void setOrderReference(String v){orderReference=v;}
    public String getPaymentReference(){return paymentReference;}public void setPaymentReference(String v){paymentReference=v;}
    public BigDecimal getAmount(){return amount;}public void setAmount(BigDecimal v){amount=v;}
    public String getCurrency(){return currency;}public void setCurrency(String v){currency=v;}
    public String getStatus(){return status;}public void setStatus(String v){status=v;}
    public String getProviderRefundReference(){return providerRefundReference;}public void setProviderRefundReference(String v){providerRefundReference=v;}
    public String getReviewNote(){return reviewNote;}public void setReviewNote(String v){reviewNote=v;}
    public User getRequestedBy(){return requestedBy;}public void setRequestedBy(User v){requestedBy=v;}
    public User getReviewedBy(){return reviewedBy;}public void setReviewedBy(User v){reviewedBy=v;}
    public Instant getCreatedAt(){return createdAt;}public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;}public void setUpdatedAt(Instant v){updatedAt=v;}
}
