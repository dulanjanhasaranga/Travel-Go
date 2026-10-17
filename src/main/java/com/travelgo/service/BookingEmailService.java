package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class BookingEmailService {
 private final TransactionalEmailService email;private final BookingHotelRepository hotels;private final SystemSettingsService settings;private final String baseUrl;private final VisaApplicationRepository visas;
 private final InvoicePdfGenerator invoiceGenerator;
 private final VisaStatusHistoryRepository history;
 public BookingEmailService(TransactionalEmailService email,BookingHotelRepository hotels,SystemSettingsService settings,VisaApplicationRepository visas,String baseUrl, InvoicePdfGenerator invoiceGenerator){this(email,hotels,settings,visas,baseUrl,invoiceGenerator,null);}
 @org.springframework.beans.factory.annotation.Autowired
 public BookingEmailService(TransactionalEmailService email,BookingHotelRepository hotels,SystemSettingsService settings,VisaApplicationRepository visas,@Value("${travelgo.public-base-url:http://localhost:8081}")String baseUrl, InvoicePdfGenerator invoiceGenerator,VisaStatusHistoryRepository history){this.email=email;this.hotels=hotels;this.settings=settings;this.baseUrl=baseUrl;this.visas=visas;this.invoiceGenerator=invoiceGenerator;this.history=history;}
 public void booking(Booking b,boolean cancelled){
  var rows=details(b);if(!cancelled)rows.put("Payment status","Not paid");
  send(b,(cancelled?"cancellation:":"booking:")+b.getId(),cancelled?"Booking cancelled":"Booking request received",cancelled?"Your booking has been cancelled.":"We have received your booking request. Your trip is awaiting confirmation.",rows,cancelled?"View your booking details for payment and refund status. Contact our travel team if you need help.":"Start your visa application from the booking details. Your officer will review your documents and quote the fees. Pay the visa quote within 24 hours; final payment is due after visa approval, three days before travel.", null, null);
 }
 public void payment(Payment p){
  Booking b=p.getBooking();var rows=details(b);rows.put("Transaction",p.getTransactionReference());rows.put("Paid at",String.valueOf(p.getPaidAt()));rows.put("Final payment","$"+p.getAmount());rows.put("Package and hotel balance","$0.00");rows.put("Payment status","Paid in full");
  
  byte[] pdfBytes = invoiceGenerator.generateInvoice(b, p);
  String pdfName = "Receipt-" + p.getId() + ".pdf";
  
  send(b,"payment:"+p.getId(),"Payment confirmation","Your final payment is complete and your booking is confirmed. Your payment receipt is attached.",rows,"View your booking details and contact your travel consultant to confirm the arrangements for your trip.", pdfName, pdfBytes);
 }
 public void completed(Booking booking){
  send(booking,"bookingCompleted:"+booking.getId(),"Trip completed","Your travel consultant has marked your trip complete.",details(booking),
      "Thank you for travelling with us. You can share your feedback from your booking details, or contact the travel team if you need help.",null,null);
 }
 public void visaUpdate(VisaApplication v, String message){
  Booking b = v.getBooking(); var rows = details(b); rows.put("Visa status", label(v.getStatus().name()));
  String revision = history == null ? v.getStatus().name() : "event:" + history.findTopByVisaApplication_IdOrderByIdDesc(v.getId())
      .filter(event -> event.getStatus() == v.getStatus()).map(event -> String.valueOf(event.getId()))
      .orElseThrow(() -> new IllegalStateException("Save the visa transition before queuing its update."));
  send(b, "visaUpdate:" + v.getId() + ":" + revision, "Visa application update", message, rows, "Open your visa application for details and any actions required.", null, null);
 }
 private LinkedHashMap<String,String> details(Booking b){
  var rows=new LinkedHashMap<String,String>();rows.put("Booking reference","TG-"+b.getId());rows.put("Package",b.getTourPackage().getName());rows.put("Destination",b.getTourPackage().getDestination().getCity()+", "+b.getTourPackage().getDestination().getCountry());rows.put("Travel date",String.valueOf(b.getTravelDate()));rows.put("Travelers",String.valueOf(b.getNumberOfTravelers()));
  BigDecimal total=b.getTotalPackageAmount().add(hotels.findByBooking_Id(b.getId()).map(BookingHotel::getHotelCost).orElse(BigDecimal.ZERO));
  rows.put("Package and hotel amount","$"+total);
  var upfront=visas.findByBooking_Id(b.getId()).map(VisaApplication::getTotalCharge);
  rows.put("Visa and documentation charges",upfront.map(v->"$"+v).orElse("Quoted after verification"));
  upfront.ifPresent(v->rows.put("Total booking charges","$"+total.add(v)));
  rows.put("Booking status",label(b.getBookingStatus().name()));return rows;
 }
 private void send(Booking b,String key,String heading,String intro,Map<String,String> rows,String next, String attachmentName, byte[] attachmentData){var s=settings.getSettings();email.queueWithAttachment(key,b.getUser().getEmail(),heading+" — TG-"+b.getId()+" | TravelGO",Map.of("heading",heading,"customerName",b.getUser().getName(),"intro",intro,"details",rows,"nextStep",next,"link",baseUrl+"/customer/bookings/"+b.getId(),"contact",s.getCompanyEmail()+" · "+s.getCompanyPhone()), attachmentName, attachmentData);}
 private String label(String value){String text=value.toLowerCase(java.util.Locale.ROOT).replace('_',' ');return Character.toUpperCase(text.charAt(0))+text.substring(1);}
}
