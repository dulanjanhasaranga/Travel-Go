package com.travelgo;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import org.springframework.boot.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.*;
import java.util.*;
/** Explicit, disposable browser fixture launcher. Never part of the production JAR. */
public class PlatformBrowserPreview {
 public static void main(String[] args){
  SpringApplication app=new SpringApplication(TravelGoApplication.class,Fixtures.class);
  app.run("--spring.profiles.active=mysql-test","--server.address=127.0.0.1","--server.port=8082","--spring.config.import=",
   "--spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true",
   "--spring.datasource.username=root","--spring.datasource.password=","--travelgo.mail.enabled=false",
   "--travelgo.mail.dispatch-enabled=false","--spring.jpa.open-in-view=true","--spring.jpa.hibernate.ddl-auto=create-drop",
   "--spring.thymeleaf.prefix=file:C:/Users/User/Downloads/ISE/ISE/src/main/resources/templates/","--spring.thymeleaf.cache=false",
   "--spring.web.resources.static-locations=file:C:/Users/User/Downloads/ISE/ISE/src/main/resources/static/");
 }
 @TestConfiguration static class Fixtures {
  @Bean ApplicationRunner browserFixtures(RoleRepository roles,UserRepository users,PasswordEncoder encoder,CatalogueExpansionService catalogue,
      BookingService bookings,ContactMessageService inquiries,org.springframework.transaction.PlatformTransactionManager manager){
   return args->{
    var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    List<User> accounts=tx.execute(s->{var result=new ArrayList<User>();for(String role:List.of("CUSTOMER","ADMIN","TRAVEL_CONSULTANT","VISA_OFFICER")){
     var r=new Role();r.setRoleName(role);roles.save(r);var u=new User("Preview "+role.toLowerCase().replace('_',' '),role.toLowerCase()+"@example.invalid",encoder.encode("Preview-only-123!"),r);users.save(u);result.add(u);}return result;});
    var packages=catalogue.install();User customer=accounts.get(0);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(customer.getEmail(),"unused",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    try{
     bookings.createBooking(new com.travelgo.dto.BookingRequest(packages.get(0).getId(),LocalDate.now().plusDays(60),1,null,List.of(new com.travelgo.dto.BookingRequest.TravelerInput("Preview traveler","TEST123",LocalDate.of(1990,1,1),"Other","Test"))));
     inquiries.submit(new com.travelgo.dto.InquiryRequest(customer.getName(),customer.getEmail(),"A relaxed London trip","Please advise on walking distances and museum visits.","123","PACKAGE",null,packages.get(0).getId(),LocalDate.now().plusDays(60),2,"1000_3000",UUID.randomUUID().toString()));
    }finally{SecurityContextHolder.clearContext();}
   };
  }
 }
}
