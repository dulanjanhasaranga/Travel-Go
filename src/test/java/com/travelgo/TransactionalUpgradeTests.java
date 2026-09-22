package com.travelgo;
import com.travelgo.service.*;
import com.travelgo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@TestPropertySource(properties={"travelgo.mail.enabled=true","travelgo.mail.dispatch-enabled=false"})
public class TransactionalUpgradeTests extends WorkflowIntegrationTests {
 @Autowired TransactionalEmailService email;
 @Autowired OutboundEmailRepository outbox;
 @Autowired EmailDeliveryService delivery;
 @Autowired UserService users;
 @Autowired CatalogueExpansionService catalogue;
 @Test void newPackagesArePersistedIdempotentlyAndUseNormalBookingFlow(){
  var added=catalogue.install();assertEquals(3,added.size());var again=catalogue.install();assertEquals(added.stream().map(com.travelgo.entity.TourPackage::getId).toList(),again.stream().map(com.travelgo.entity.TourPackage::getId).toList());
  for(var p:added){assertNotNull(p.getItinerary());login(customer);var b=bookings.createBooking(new com.travelgo.dto.BookingRequest(p.getId(),java.time.LocalDate.of(2030,2,1),1,null,List.of(traveler())));assertEquals(p.getId(),b.getTourPackage().getId());assertEquals(0,p.getBasePrice().compareTo(b.getTotalPackageAmount()));}
 }
 @MockitoBean JavaMailSender sender;
 @Test void successfulDeliveryIsClaimedOnceAndUsesHtml()throws Exception{
  String key=UUID.randomUUID().toString();tx.executeWithoutResult(s->email.queue(key,customer.getEmail(),"Booking confirmation",content()));
  var mime=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
  when(sender.createMimeMessage()).thenReturn(mime);
  var record=outbox.findByEventKey(key).orElseThrow();delivery.deliver(record.getId());delivery.deliver(record.getId());
  assertEquals("SENT",outbox.findById(record.getId()).orElseThrow().getStatus());verify(sender,times(1)).send(mime);
  assertTrue(mime.getContentType().contains("multipart"));assertEquals(customer.getEmail(),mime.getAllRecipients()[0].toString());
 }
 @Test void concurrentBookingRetryCreatesOneBookingAndEmail()throws Exception{
  String key=UUID.randomUUID().toString();var request=new com.travelgo.dto.BookingRequest(tour.getId(),java.time.LocalDate.of(2030,2,1),1,null,List.of(traveler()));
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{java.util.concurrent.Callable<Long> task=()->{login(customer);try{start.await();return bookings.createBooking(request,key).getId();}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}};
   var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertEquals(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
  }finally{pool.shutdownNow();}
  var saved=bookingRepo.findByRequestKey(customer.getId()+":"+key).orElseThrow();assertTrue(outbox.findByEventKey("booking:"+saved.getId()).isPresent());
 }
 @Test void bookingRetryQueuesOneEmailAndChangedPayloadIsRejected(){
  String key=UUID.randomUUID().toString();var request=new com.travelgo.dto.BookingRequest(tour.getId(),java.time.LocalDate.of(2030,2,1),1,null,List.of(traveler()));
  var b=bookings.createBooking(request,key);assertEquals(b.getId(),bookings.createBooking(request,key).getId());
  assertEquals(customer.getEmail(),outbox.findByEventKey("booking:"+b.getId()).orElseThrow().getRecipient());
  assertThrows(IllegalStateException.class,()->bookings.createBooking(new com.travelgo.dto.BookingRequest(tour.getId(),java.time.LocalDate.of(2030,3,1),1,null,List.of(traveler())),key));
  long before=outbox.count();assertThrows(IllegalArgumentException.class,()->bookings.createBooking(new com.travelgo.dto.BookingRequest(tour.getId(),java.time.LocalDate.of(2030,2,1),1,Long.MAX_VALUE,List.of(traveler())),UUID.randomUUID().toString()));assertEquals(before,outbox.count());
 }
 @Test void fullPaymentQueuesOnlyOneCorrectEmail()throws Exception{
  var b=create(true);var v=processing(b);login(officer);visas.approveVisa(v.getId());login(customer);
  var p=pay(b,com.travelgo.enums.PaymentType.FULL_PACKAGE,"160.00");assertEquals(p.getId(),pay(b,com.travelgo.enums.PaymentType.FULL_PACKAGE,"160.00").getId());
  var e=outbox.findByEventKey("payment:"+p.getId()).orElseThrow();assertEquals(customer.getEmail(),e.getRecipient());assertTrue(e.getHtmlBody().contains("Fully Paid"));assertTrue(e.getHtmlBody().contains("160.00"));
 }
 Map<String,Object> content(){return Map.of("heading","Request received","customerName","<script>alert(1)</script>","intro","Booking received","details",Map.of("Reference","TG-1"),"nextStep","Upload documents","link","http://localhost:8080/customer/bookings/1","contact","Travel team");}
 @Test void queueIsTransactionalEscapesContentAndSuppressesDuplicates(){
  String key=UUID.randomUUID().toString();
  tx.executeWithoutResult(s->{email.queue(key,customer.getEmail(),"Booking received",content());email.queue(key,customer.getEmail(),"Booking received",content());});
  var record=outbox.findByEventKey(key).orElseThrow();assertEquals("QUEUED",record.getStatus());assertFalse(record.getHtmlBody().contains("<script>"));assertTrue(record.getHtmlBody().contains("&lt;script&gt;"));
  String rollback=UUID.randomUUID().toString();tx.executeWithoutResult(s->{email.queue(rollback,customer.getEmail(),"Rollback",content());s.setRollbackOnly();});assertTrue(outbox.findByEventKey(rollback).isEmpty());
 }
 @Test void smtpFailureDoesNotLoseBusinessDataOrAutomaticallyResend(){
  String key=UUID.randomUUID().toString();tx.executeWithoutResult(s->email.queue(key,customer.getEmail(),"Booking",content()));
  when(sender.createMimeMessage()).thenThrow(new org.springframework.mail.MailSendException("private failure"));
  var record=outbox.findByEventKey(key).orElseThrow();delivery.deliver(record.getId());delivery.deliver(record.getId());
  assertEquals("REVIEW_REQUIRED",outbox.findById(record.getId()).orElseThrow().getStatus());verify(sender,times(1)).createMimeMessage();
 }
 @Test void emailAccountsValidateNormalizeAndRejectDuplicates(){
  String address=UUID.randomUUID()+"@example.invalid";var u=users.registerCustomer("New Traveler",address.toUpperCase(),"Safe-password123",null,null);
  assertEquals(address,u.getEmail());assertNotEquals("Safe-password123",u.getPassword());
  assertThrows(RuntimeException.class,()->users.registerCustomer("New Traveler",address,"Safe-password123",null,null));
  assertThrows(IllegalArgumentException.class,()->users.registerCustomer("New Traveler","invalid","Safe-password123",null,null));
 }
}
