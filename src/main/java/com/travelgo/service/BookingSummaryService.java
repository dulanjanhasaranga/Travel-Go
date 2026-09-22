package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service("bookingSummary")
@Transactional(readOnly=true)
public class BookingSummaryService {
 private final PaymentRepository payments;private final RefundRepository refunds;private final WorkflowRules rules;
 public BookingSummaryService(PaymentRepository payments,RefundRepository refunds,WorkflowRules rules){this.payments=payments;this.refunds=refunds;this.rules=rules;}
 public record Summary(String status,BigDecimal packageAndHotel,BigDecimal paid,BigDecimal packageBalance){}
 public Summary forBooking(Booking b){
  BigDecimal total=rules.expected(b,PaymentType.FULL_PACKAGE),paid=BigDecimal.ZERO,finalPaid=BigDecimal.ZERO;
  for(var p:payments.findByBooking_Id(b.getId()))if(p.getPaymentStatus()==PaymentStatus.PAID||p.getPaymentStatus()==PaymentStatus.REFUNDED){
   BigDecimal net=p.getAmount().subtract(refunds.findByPayment_Id(p.getId()).filter(r->r.getStatus()==RefundStatus.REFUND_PROCESSED).map(Refund::getAmount).orElse(BigDecimal.ZERO));paid=paid.add(net);if(p.getPaymentType()==PaymentType.FULL_PACKAGE)finalPaid=finalPaid.add(net);
  }
  String status=paid.signum()>0?"Partially paid":"Not paid";
  try{rules.requirePaid(b,PaymentType.VISA_DOCUMENTATION);rules.requirePaid(b,PaymentType.FULL_PACKAGE);status=rules.visa(b).getStatus()==VisaStatus.APPROVED&&b.getBookingStatus()==BookingStatus.CONFIRMED?"Fully Paid":"Needs staff review";}catch(IllegalArgumentException|IllegalStateException e){if(finalPaid.signum()>0)status="Needs staff review";}
  if(b.getBookingStatus()==BookingStatus.CANCELLED)status="Cancelled";
  return new Summary(status,total,paid,total.subtract(finalPaid).max(BigDecimal.ZERO));
 }
 public java.util.List<Payment> records(Long bookingId){return payments.findByBooking_IdOrderByCreatedAtDesc(bookingId);}
}
