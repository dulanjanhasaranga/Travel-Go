package com.travelgo;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import org.springframework.boot.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.*;
import java.util.*;
/** Explicit, disposable browser fixture launcher. Never part of the production JAR. */
public class PlatformBrowserPreview {
 public static void main(String[] args){
  System.setProperty("spring.devtools.restart.enabled", "false");
  SpringApplication app=new SpringApplication(TravelGoApplication.class,Fixtures.class);
  app.run("--spring.profiles.active=test","--server.address=127.0.0.1","--server.port=8082","--spring.config.import=",
   "--server.servlet.session.cookie.name=TRAVELGO_QA_SESSION",
   "--spring.datasource.url=jdbc:h2:mem:travelgo-browser-qa;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
   "--spring.datasource.username=sa","--spring.datasource.password=","--travelgo.mail.enabled=false",
   "--travelgo.mail.dispatch-enabled=false","--spring.jpa.open-in-view=true","--spring.jpa.hibernate.ddl-auto=create-drop",
   "--travelgo.upload-directory=target/browser-qa-uploads",
   "--spring.thymeleaf.prefix=file:C:/Users/User/Downloads/ISE/ISE/src/main/resources/templates/","--spring.thymeleaf.cache=false",
   "--spring.web.resources.static-locations=file:C:/Users/User/Downloads/ISE/ISE/src/main/resources/static/");
 }
 @TestConfiguration @Import(TestDatabaseSafetyConfiguration.class) static class Fixtures {
  @Bean ApplicationRunner browserFixtures(RoleRepository roles,UserRepository users,PasswordEncoder encoder,CatalogueExpansionService catalogue,
      BookingService bookings,BookingRepository bookingRecords,ContactMessageService inquiries,DepartureRepository departures,HotelRepository hotels,
      VisaApplicationService visaApplications,VisaDocumentService visaDocuments,TravelPolicyService policies,
      VisaChecklistService checklists,SupplierReservationService suppliers,SupplierHoldRepository supplierRecords,
      PaymentService payments,CancellationRequestService cancellations,java.time.Clock clock,
      PermissionRepository permissions,SystemSettingsRepository settings,org.springframework.transaction.PlatformTransactionManager manager){
   return args->{
    // Explicitly seed reference permissions in this guarded, disposable database only.
    new com.travelgo.config.DataInitializer(roles,permissions,users,settings,encoder,"","","").run();
    var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    List<User> accounts=tx.execute(s->{var result=new ArrayList<User>();for(String role:List.of("CUSTOMER","ADMIN","TRAVEL_CONSULTANT","VISA_OFFICER")){
     var r=roles.findByRoleName(role).orElseThrow();var u=new User("Preview "+role.toLowerCase().replace('_',' '),role.toLowerCase()+"@example.invalid",encoder.encode("Preview-only-123!"),r);users.save(u);result.add(u);}return result;});
    var packages=catalogue.install();User customer=accounts.get(0),admin=accounts.get(1),consultant=accounts.get(2),officer=accounts.get(3);
    login(admin);
    String policyText=("Disposable browser QA policy. This text exists only in the isolated H2 preview, "
      +"is not real agency advice and does not define actual customer fees or entitlements. ").repeat(2);
    var draftPolicy=policies.draft("Synthetic browser QA policy — not agency terms",policyText,policyText,policyText);
    var policy=policies.publish(draftPolicy.getId(),null,true);
    login(officer);
    var checklistDraft=checklists.createDraft(new com.travelgo.dto.VisaChecklistRequest(
      packages.get(0).getDestination().getId(),com.travelgo.enums.VisaType.VISIT,
      "https://example.invalid/browser-qa/no-official-visa-requirements",LocalDate.now(clock),7,5,10,
      "Synthetic browser QA checklist. The example.invalid URL and document list are test fixtures, not official visa requirements.",
      "Synthetic passport fixture\nSynthetic supporting evidence fixture","Synthetic optional itinerary fixture"));
    var checklist=checklists.publish(checklistDraft.getId(),checklistDraft.getLockVersion());
    tx.executeWithoutResult(s->{
     var hotel=new Hotel();hotel.setName("Preview Riverside Hotel");hotel.setDestination(packages.get(0).getDestination());
     hotel.setAddress("Synthetic browser fixture address");hotel.setPricePerNight(new java.math.BigDecimal("100.00"));
     hotel.setDescription("Disposable accommodation fixture for mobile booking checks.");hotel.setImage("/images/london.jpg");
     hotels.save(hotel); // Null rating exercises the valid, unrated-hotel presentation.
    });
    LocalDate travelDate=departures.findByTourPackage_Id(packages.get(0).getId()).stream()
      .map(Departure::getDepartureDate).filter(date->date.isAfter(LocalDate.now().plusDays(3)))
      .sorted().findFirst().orElseThrow(()->new IllegalStateException("Browser fixture requires an eligible departure."));
    login(customer);
    try{
     var browserBooking=bookings.createBooking(new com.travelgo.dto.BookingRequest(packages.get(0).getId(),travelDate,1,null,List.of(new com.travelgo.dto.BookingRequest.TravelerInput("Preview traveler","TEST123",LocalDate.of(1990,1,1),"Other","Test"))),UUID.randomUUID().toString(),true,policy.getId());
     var browserVisa=visaApplications.createVisaApplication(browserBooking,com.travelgo.enums.VisaType.VISIT);
     var fixtureImage=new java.io.ByteArrayOutputStream();
     javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",fixtureImage);
     visaDocuments.upload(browserVisa.getId(),"Preview document (synthetic)",
       new org.springframework.mock.web.MockMultipartFile("file","preview.png","image/png",fixtureImage.toByteArray()),checklist.getItems().get(0).getId());
     // Distinct disposable examples exercise the application form and historical review notice.
     bookings.createBooking(new com.travelgo.dto.BookingRequest(packages.get(1).getId(),travelDate,2,null,
       List.of(new com.travelgo.dto.BookingRequest.TravelerInput("Preview traveler two","TEST234",LocalDate.of(1990,1,1),"Other","Test"),
               new com.travelgo.dto.BookingRequest.TravelerInput("Preview traveler three","TEST345",LocalDate.of(1991,1,1),"Other","Test"))),UUID.randomUUID().toString(),true,policy.getId());
     var historicalBooking=bookings.createBooking(new com.travelgo.dto.BookingRequest(packages.get(2).getId(),travelDate,1,null,
       List.of(new com.travelgo.dto.BookingRequest.TravelerInput("Historical fixture traveler","TEST456",LocalDate.of(1990,1,1),"Other","Test"))),UUID.randomUUID().toString(),true,policy.getId());
     tx.executeWithoutResult(s->{var record=bookingRecords.findById(historicalBooking.getId()).orElseThrow();
       record.setBookingStatus(com.travelgo.enums.BookingStatus.CONFIRMED);bookingRecords.save(record);});
     inquiries.submit(new com.travelgo.dto.InquiryRequest(customer.getName(),customer.getEmail(),"A relaxed London trip","Please advise on walking distances and museum visits.","123","PACKAGE",null,packages.get(0).getId(),travelDate,2,"1000_3000",UUID.randomUUID().toString()));
     // These examples use the same role-restricted services as the site; all evidence is explicitly synthetic.
     login(consultant);var supplier=supplierRecords.findByBooking_Id(browserBooking.getId()).orElseThrow();
     suppliers.confirm(browserBooking.getId(),supplier.getVersion(),"Synthetic QA supplier","QA-EXTERNAL-NO-REAL-RESERVATION",
       "Disposable preview evidence only. No real hotel or tour supplier has been contacted or confirmed.",true);
     for(int i=0;i<2;i++){
      login(customer);
      var cancellationBooking=bookings.createBooking(new com.travelgo.dto.BookingRequest(packages.get(0).getId(),travelDate,1,null,
        List.of(new com.travelgo.dto.BookingRequest.TravelerInput("Synthetic cancellation traveler "+i,"QA-CANCEL-"+i,
          LocalDate.of(1990,1,1),"Other","Test"))),UUID.randomUUID().toString(),true,policy.getId());
      var visa=visaApplications.createVisaApplication(cancellationBooking,com.travelgo.enums.VisaType.VISIT);
      for(var item:checklist.getItems())if(item.isRequired())visaDocuments.upload(visa.getId(),"Synthetic QA upload: "+item.getLabel(),
        new org.springframework.mock.web.MockMultipartFile("file","synthetic-qa.png","image/png",fixtureImage.toByteArray()),item.getId());
      login(officer);visaApplications.markDocumentsVerified(visa.getId());
      visaApplications.setVisaCharges(visa.getId(),new java.math.BigDecimal("30.00"),new java.math.BigDecimal("10.00"));
      login(customer);var payment=new Payment();payment.setBooking(cancellationBooking);payment.setPaymentType(com.travelgo.enums.PaymentType.VISA_DOCUMENTATION);
      payment.setAmount(new java.math.BigDecimal("40.00"));payment.setPaymentMethod("Simulated browser QA payment");
      payment.setTransactionReference("SIM-BROWSER-QA-"+UUID.randomUUID());var paid=payments.processPayment(payment);
      var request=cancellations.request(cancellationBooking.getId(),"Synthetic preview cancellation request; no real trip or payment.");
      login(consultant);var quote=cancellations.quote(request.getId(),request.getVersion(),policy.getId(),
        Map.of(paid.getId(),new java.math.BigDecimal("10.00")),
        "Synthetic browser QA quote only: $10 simulated refund; $30 synthetic retained charge. These are not agency refund rules.",LocalDateTime.now(clock).plusHours(6));
      if(i==1){login(customer);cancellations.accept(quote.getId(),quote.getVersion(),true);}
     }
    }finally{SecurityContextHolder.clearContext();}
   };
  }
 }
 private static void login(User user){
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(),"unused",
    WorkflowIntegrationTests.authorities(user.getRole().getRoleName())));
 }
}
