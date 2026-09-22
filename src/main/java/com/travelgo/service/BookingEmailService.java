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
 public BookingEmailService(TransactionalEmailService email,BookingHotelRepository hotels,SystemSettingsService settings,VisaApplicationRepository visas,@Value("${travelgo.public-base-url:http://localhost:8080}")String baseUrl, InvoicePdfGenerator invoiceGenerator){this.email=email;this.hotels=hotels;this.settings=settings;this.baseUrl=baseUrl;this.visas=visas;this.invoiceGenerator=invoiceGenerator;}
 public void booking(Booking b,boolean cancelled){
  var rows=details(b);rows.put("Payment status","Not paid");
  send(b,(cancelled?"cancellation:":"booking:")+b.getId(),cancelled?"Booking cancellation":"Booking request received",cancelled?"Your request has been cancelled.":"Your booking request has been saved. Your trip is not yet confirmed.",rows,cancelled?"Contact the travel team if you would like to plan another journey.":"Open your booking and submit visa documents. Pay verified visa charges within 24 hours, then pay the package balance after visa approval and before the three-day departure deadline.", null, null);
 }
 public void payment(Payment p){
  Booking b=p.getBooking();var rows=details(b);rows.put("Transaction",p.getTransactionReference());rows.put("Paid at",String.valueOf(p.getPaidAt()));rows.put("Final payment received","$"+p.getAmount());rows.put("Package/hotel balance","$0.00");rows.put("Payment status","Fully Paid");
  
  byte[] pdfBytes = invoiceGenerator.generateInvoice(b, p);
  String pdfName = "Invoice_INV-" + p.getId() + ".pdf";
  
  send(b,"payment:"+p.getId(),"Payment confirmation","Your payment succeeded and your booking is confirmed. Please find your official invoice attached.",rows,"View your booking and payment records in your account. Contact the travel team to confirm operational travel arrangements.", pdfName, pdfBytes);
 }
 public void visaUpdate(VisaApplication v, String message){
  Booking b = v.getBooking(); var rows = details(b); rows.put("Visa Status", v.getStatus().name());
  send(b, "visaUpdate:" + v.getId() + ":" + v.getStatus().name(), "Visa Application Update", "Your visa application status has been updated: " + message, rows, "View your visa application progress in your dashboard.", null, null);
 }
 private LinkedHashMap<String,String> details(Booking b){
  var rows=new LinkedHashMap<String,String>();rows.put("Booking reference","TG-"+b.getId());rows.put("Package",b.getTourPackage().getName());rows.put("Destination",b.getTourPackage().getDestination().getCity()+", "+b.getTourPackage().getDestination().getCountry());rows.put("Travel date",String.valueOf(b.getTravelDate()));rows.put("Travelers",String.valueOf(b.getNumberOfTravelers()));
  BigDecimal total=b.getTotalPackageAmount().add(hotels.findByBooking_Id(b.getId()).map(BookingHotel::getHotelCost).orElse(BigDecimal.ZERO));
  rows.put("Package and hotel amount","$"+total);
  var upfront=visas.findByBooking_Id(b.getId()).map(VisaApplication::getTotalCharge);
  rows.put("Visa and documentation charges",upfront.map(v->"$"+v).orElse("Quoted after verification"));
  upfront.ifPresent(v->rows.put("Total booking charges","$"+total.add(v)));
  rows.put("Booking status",b.getBookingStatus().name());return rows;
 }
 private void send(Booking b,String key,String heading,String intro,Map<String,String> rows,String next, String attachmentName, byte[] attachmentData){var s=settings.getSettings();email.queueWithAttachment(key,b.getUser().getEmail(),heading+" — TG-"+b.getId()+" | TravelGO",Map.of("heading",heading,"customerName",b.getUser().getName(),"intro",intro,"details",rows,"nextStep",next,"link",baseUrl+"/customer/bookings/"+b.getId(),"contact",s.getCompanyEmail()+" · "+s.getCompanyPhone()), attachmentName, attachmentData);}
}
