package com.travelgo.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="cancellation_refund_lines",uniqueConstraints=@UniqueConstraint(columnNames={"request_id","payment_id"}))
public class CancellationRefundLine {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="request_id") private CancellationRequest request;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="payment_id") private Payment payment;
    @Column(nullable=false,precision=10,scale=2) private BigDecimal amount;
    public Long getId(){return id;}
    public CancellationRequest getRequest(){return request;}public void setRequest(CancellationRequest v){request=v;}
    public Payment getPayment(){return payment;}public void setPayment(Payment v){payment=v;}
    public BigDecimal getAmount(){return amount;}public void setAmount(BigDecimal v){amount=v;}
}
