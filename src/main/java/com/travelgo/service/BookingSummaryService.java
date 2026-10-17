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
 public record Summary(String status,BigDecimal packageAndHotel,BigDecimal paid,BigDecimal packageBalance,
                       boolean reviewRequired,String explanation){}
 public Summary forBooking(Booking b){
  BigDecimal total=rules.expected(b,PaymentType.FULL_PACKAGE),paid=BigDecimal.ZERO,finalPaid=BigDecimal.ZERO;
  boolean processedRefund=false;
  for(var p:payments.findByBooking_Id(b.getId()))if(p.getPaymentStatus()==PaymentStatus.PAID||p.getPaymentStatus()==PaymentStatus.REFUNDED){
   BigDecimal refunded=refunds.findByPayment_Id(p.getId()).filter(r->r.getStatus()==RefundStatus.REFUND_PROCESSED).map(Refund::getAmount).orElse(BigDecimal.ZERO);
   processedRefund|=refunded.signum()>0;
   BigDecimal net=p.getAmount().subtract(refunded);paid=paid.add(net);if(p.getPaymentType()==PaymentType.FULL_PACKAGE)finalPaid=finalPaid.add(net);
  }
  String status=paid.signum()>0?"Partially paid":"Not paid";
  boolean reviewRequired=false;
  try{
   rules.requirePaid(b,PaymentType.VISA_DOCUMENTATION);rules.requirePaid(b,PaymentType.FULL_PACKAGE);
   boolean complete=rules.visa(b).getStatus()==VisaStatus.APPROVED&&(b.getBookingStatus()==BookingStatus.CONFIRMED||b.getBookingStatus()==BookingStatus.COMPLETED)&&!processedRefund;
   status=complete?"Fully Paid":"Review needed";reviewRequired=!complete;
  }catch(IllegalArgumentException|IllegalStateException e){
   // A historical confirmation is not evidence that its payments and visa reconcile.
   reviewRequired=finalPaid.signum()>0||b.getBookingStatus()==BookingStatus.CONFIRMED||b.getBookingStatus()==BookingStatus.COMPLETED;
   if(reviewRequired)status="Review needed";
  }
  if(b.getBookingStatus()==BookingStatus.CANCELLED){status="Cancelled";reviewRequired=false;}
  String explanation=reviewRequired
   ?"Please contact your travel consultant to confirm your payment and visa status."
   :status.equals("Fully Paid")?"Visa approved. All required payments are complete."
   :status.equals("Cancelled")?"View this booking's payments and refunds in the booking details."
   :paid.signum()>0?"View your visa fees and package payments in the booking details."
   :"No payments yet.";
  return new Summary(status,total,paid,total.subtract(finalPaid).max(BigDecimal.ZERO),reviewRequired,explanation);
 }
 public java.util.List<Payment> records(Long bookingId){return payments.findByBooking_IdOrderByCreatedAtDesc(bookingId);}
}
