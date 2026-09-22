package com.travelgo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.travelgo.service.ContactMessageService;
import com.travelgo.repository.ContactMessageRepository;
import com.travelgo.dto.InquiryRequest;
import com.travelgo.enums.ContactMessageStatus;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
public class InquiryUpgradeTests extends TransactionalUpgradeTests {
 @Autowired ContactMessageService inquiries;
 @Autowired ContactMessageRepository inquiryRepo;
 InquiryRequest request(String key){return new InquiryRequest("Traveler",customer.getEmail(),"Plan a trip","Please help with dates.","123","PACKAGE",tour.getDestination().getId(),tour.getId(),java.time.LocalDate.of(2030,2,1),2,"1000_3000",key);}
 @Test void inquiryPersistsPreferencesQueuesAcknowledgementAndProtectsHistory(){
  var q=request(UUID.randomUUID().toString());var m=inquiries.submit(q);assertNotNull(m.getId());assertEquals("INQ-"+m.getId(),m.getReference());assertTrue(outbox.findByEventKey("inquiry:"+m.getId()).isPresent());
  assertTrue(inquiries.forCustomer().stream().anyMatch(i->i.getId().equals(m.getId())));login(other);assertTrue(inquiries.forCustomer().stream().noneMatch(i->i.getId().equals(m.getId())));
  assertThrows(IllegalArgumentException.class,()->inquiries.submit(q));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->inquiries.updateStatus(m.getId(),ContactMessageStatus.IN_PROGRESS,"",m.getVersion()));
  login(admin);inquiries.updateStatus(m.getId(),ContactMessageStatus.IN_PROGRESS,"Discuss dates",m.getVersion());
  assertThrows(IllegalStateException.class,()->inquiries.updateStatus(m.getId(),ContactMessageStatus.CLOSED,"",m.getVersion()));
 }
 @Test void invalidInquiryDoesNotCreateEmailOrRecord(){
  long before=inquiryRepo.count(),mail=outbox.count();var q=new InquiryRequest("","wrong","Subject","Message",null,"GENERAL",null,null,null,0,"",UUID.randomUUID().toString());
  assertThrows(IllegalArgumentException.class,()->inquiries.submit(q));assertEquals(before,inquiryRepo.count());assertEquals(mail,outbox.count());
 }
}
