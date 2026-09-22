package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Integration audit. Tests named reproduce... intentionally document current defects, not correct behavior. */
public class FeatureConnectivityAuditIT extends WorkflowIntegrationTests {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired PermissionRepository permissions;
    @Autowired ContactMessageRepository inquiries;
    @Autowired ReviewRepository reviews;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired PasswordEncoder encoder;
    @Autowired SystemSettingsService settings;
    @Autowired org.springframework.jdbc.core.JdbcTemplate sql;
    void submit(User actor, String path, Map<String,String> params) throws Exception {
        SecurityContextHolder.clearContext(); var request=post(path).with(csrf());
        if(actor!=null) request.with(user(actor.getEmail()).roles(actor.getRole().getRoleName()));
        params.forEach(request::param);
        mvc.perform(request).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
    }
    String page(User actor,String path) throws Exception {
        SecurityContextHolder.clearContext(); var request=get(path);
        if(actor!=null) request.with(user(actor.getEmail()).roles(actor.getRole().getRoleName()));
        var result=mvc.perform(request).andExpect(status().isOk()).andReturn();
        assertNotNull(result.getModelAndView(),path); assertFalse(result.getModelAndView().getViewName().equals("error"),path);
        return result.getResponse().getContentAsString();
    }
    @Test void everyControllerPageRendersAndServerFormsMatchRoutesAndRequiredParameters() throws Exception {
        Booking b=create(true); VisaApplication v=quote(b); var doc=documentRepo.findByVisaApplication_Id(v.getId()).get(0);
        ContactMessage inquiry=new ContactMessage();inquiry.setSenderName("Audit");inquiry.setSenderEmail(customer.getEmail());inquiry.setSubject("Audit inquiry");inquiry.setMessage("Please advise");inquiries.save(inquiry);
        List<String> report=new ArrayList<>(), failures=new ArrayList<>(); int forms=0;
        for(var entry:mappings.getHandlerMethods().entrySet()) {
            if(!entry.getValue().getBeanType().getPackageName().equals("com.travelgo.controller") || !entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET)) continue;
            for(String pattern:entry.getKey().getPatternValues()) {
                if(pattern.startsWith("/visa-documents")) continue; // Covered as binary response in inherited tests.
                Long id=pattern.startsWith("/destinations/")?tour.getDestination().getId():pattern.contains("/inquiries/")?inquiry.getId():(pattern.startsWith("/packages/")||pattern.startsWith("/staff/packages/"))?tour.getId():pattern.startsWith("/admin/roles/")?consultant.getRole().getId():pattern.startsWith("/admin/staff/")?officer.getId():pattern.startsWith("/admin/users/")?customer.getId():b.getId();
                String path=pattern.replaceAll("\\{[^}]+}",id.toString());
                User actor=path.startsWith("/admin")?admin:path.startsWith("/staff/visas")||path.startsWith("/staff/payments")||path.equals("/staff/dashboard")?officer:path.startsWith("/staff")?consultant:path.startsWith("/customer")||path.startsWith("/notifications")||path.startsWith("/packages/")?customer:null;
                try {
                    if (pattern.equals("/notifications/feed")) {
                        SecurityContextHolder.clearContext();
                        mvc.perform(get(path).with(user(customer.getEmail()).roles("CUSTOMER")))
                            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                            .andExpect(jsonPath("$.unreadCount").isNumber()).andExpect(jsonPath("$.items").isArray());
                        report.add("PASS JSON GET " + pattern); continue;
                    }
                    String html=page(actor,path); report.add("PASS GET "+pattern);
                    Matcher form=Pattern.compile("<form\\b([^>]*)>(.*?)</form>",Pattern.DOTALL|Pattern.CASE_INSENSITIVE).matcher(html);
                    while(form.find()) {
                        String action=attribute(form.group(1),"action"),method=attribute(form.group(1),"method");
                        if(action==null||action.equals("#")||action.isBlank()) continue;
                        if(action.equals("/auth/login")||action.equals("/auth/logout")) { forms++; continue; }
                        var req=new MockHttpServletRequest(method==null?"GET":method.toUpperCase(),action.split("\\?")[0]);
                        ServletRequestPathUtils.parseAndCache(req); var handler=mappings.getHandler(req);
                        if(handler==null) { failures.add(path+" -> missing form route "+action); continue; }
                        var hm=(org.springframework.web.method.HandlerMethod)handler.getHandler();
                        for(var param:hm.getMethodParameters()) {
                            var rp=param.getParameterAnnotation(RequestParam.class);
                            if(rp!=null&&rp.required()&&rp.defaultValue().equals(org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE)) {
                                String name=rp.name().isBlank()?rp.value():rp.name();if(name.isBlank())name=param.getParameterName();
                                if(!form.group(2).contains("name=\""+name+"\"")&&!form.group(2).contains("name='"+name+"'")) failures.add(path+" -> form "+action+" missing "+name);
                            }
                        }
                        if("post".equalsIgnoreCase(method)&&!form.group(2).contains("name=\"_csrf\"")) failures.add(path+" -> missing CSRF "+action);
                        forms++;
                    }
                } catch(Exception | AssertionError ex) { failures.add(pattern+" -> "+ex.getClass().getSimpleName()+": "+ex.getMessage()); }
            }
        }
        report.add("Forms checked: "+forms); report.addAll(failures);
        Files.createDirectories(Path.of("target")); Files.write(Path.of("target/feature-route-audit.txt"),report);
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
    String attribute(String tag,String name) {
        Matcher m=Pattern.compile("(?:^|\\s)"+name+"=[\"']([^\"']*)[\"']",Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find()?m.group(1):null;
    }
    @Test void registrationLoginSessionLogoutAndDisabledLoginAreConnectedToSql() throws Exception {
        String email=UUID.randomUUID()+"@example.invalid", password="AuditPass123!";
        submit(null,"/auth/register",Map.of("name","Audit Customer","email",email,"password",password,"confirmPassword",password,"phone","123","address","Audit"));
        User saved=userRepo.findByEmail(email).orElseThrow(); assertTrue(encoder.matches(password,saved.getPassword()));
        SecurityContextHolder.clearContext();
        var loginResult=mvc.perform(post("/auth/login").with(csrf()).param("email",email).param("password",password).param("remember-me","on"))
            .andExpect(redirectedUrl("/customer/dashboard")).andReturn();
        assertTrue(Arrays.stream(loginResult.getResponse().getCookies()).noneMatch(c -> c.getName().equals("remember-me")), "Remember-me cookie is not implemented.");
        var session=(org.springframework.mock.web.MockHttpSession)loginResult.getRequest().getSession(false);
        assertNotNull(session); mvc.perform(get("/customer/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(post("/auth/logout").session(session).with(csrf())).andExpect(redirectedUrl("/auth/login?logout=true"));
        submit(admin,"/admin/users/"+saved.getId()+"/toggle-status",Map.of());
        mvc.perform(post("/auth/login").with(csrf()).param("email",email).param("password",password)).andExpect(redirectedUrl("/auth/login?error=true"));
    }
    @Test void catalogueCreateEditSearchAndDeleteReachSqlAndPublicPages() throws Exception {
        String marker="Audit-"+UUID.randomUUID();
        submit(consultant,"/staff/destinations/create",Map.of("country",marker,"city","Audit city"));
        Destination d=destinationRepo.findAll().stream().filter(x->marker.equals(x.getCountry())).findFirst().orElseThrow();
        submit(consultant,"/staff/categories/create",Map.of("name",marker));
        PackageCategory c=categoryRepo.findAll().stream().filter(x->marker.equals(x.getName())).findFirst().orElseThrow();
        submit(consultant,"/staff/hotels/create",Map.of("name",marker,"destinationId",d.getId().toString(),"address","Audit hotel address","pricePerNight","25.00"));
        Hotel h=hotelRepo.findAll().stream().filter(x->marker.equals(x.getName())).findFirst().orElseThrow();
        Map<String,String> packageData=Map.of("name",marker,"categoryId",c.getId().toString(),"destinationId",d.getId().toString(),"basePrice","250.00","durationDays","5","maxCapacity","8");
        submit(consultant,"/staff/packages/create",packageData);
        TourPackage p=packageRepo.findAll().stream().filter(x->marker.equals(x.getName())).findFirst().orElseThrow();
        assertTrue(page(null,"/packages?search="+marker).contains(marker)); assertTrue(page(null,"/destinations?search="+marker).contains(marker));
        submit(consultant,"/staff/packages/"+p.getId()+"/edit",packageData);
        submit(consultant,"/staff/hotels/"+h.getId()+"/edit",Map.of("name",marker,"destinationId",d.getId().toString(),"address","Updated address","pricePerNight","30.00"));
        assertEquals("Updated address",hotelRepo.findById(h.getId()).orElseThrow().getAddress());
        submit(consultant,"/staff/categories/"+c.getId()+"/edit",Map.of("name",marker,"description","Updated"));
        submit(consultant,"/staff/destinations/"+d.getId()+"/edit",Map.of("country",marker,"city","Updated city"));
        submit(consultant,"/staff/packages/"+p.getId()+"/delete",Map.of()); assertFalse(packageRepo.findById(p.getId()).orElseThrow().isActive());
        submit(consultant,"/staff/hotels/"+h.getId()+"/delete",Map.of());
        // Retained packages keep their destination/category references intact.
        assertTrue(categoryRepo.existsById(c.getId())); assertTrue(destinationRepo.existsById(d.getId()));
    }
    @Test void adminStaffUserAndSettingsFormsPersistAndRender() throws Exception {
        String email=UUID.randomUUID()+"@example.invalid";
        submit(admin,"/admin/staff/create",Map.of("name","Audit staff","email",email,"password","AuditPass123!","roleName","VISA_OFFICER","phone","123","address","Audit"));
        User staff=userRepo.findByEmail(email).orElseThrow();
        submit(admin,"/admin/staff/"+staff.getId()+"/update",Map.of("name","Edited staff","phone","456","address","Updated","roleName","TRAVEL_CONSULTANT"));
        assertEquals("TRAVEL_CONSULTANT",userRepo.findById(staff.getId()).orElseThrow().getRole().getRoleName());
        submit(admin,"/admin/staff/"+staff.getId()+"/toggle-status",Map.of()); assertFalse(userRepo.findById(staff.getId()).orElseThrow().isActive());
        submit(admin,"/admin/users/"+customer.getId()+"/update",Map.of("name","Edited customer","phone","789","address","Updated customer"));
        assertTrue(page(admin,"/admin/users/"+customer.getId()).contains("Edited customer"));
        submit(admin,"/admin/users/"+other.getId()+"/assign-role",Map.of("roleId",officer.getRole().getId().toString()));
        assertEquals("VISA_OFFICER",userRepo.findById(other.getId()).orElseThrow().getRole().getRoleName());
        submit(admin,"/admin/settings/company",Map.of("companyName","Audit Travel","companyDescription","Audit description","websiteUrl","https://example.invalid"));
        submit(admin,"/admin/settings/contact",Map.of("companyEmail","audit@example.invalid","companyPhone","123","companyAddress","Audit address"));
        submit(admin,"/admin/settings/hours",Map.of("businessHours","Audit hours"));
        assertTrue(page(null,"/contact").contains("Audit address")); assertTrue(page(null,"/contact").contains("Audit hours"));
        try {
            submit(admin,"/admin/settings/status",Map.of("websiteStatus","MAINTENANCE","maintenanceMessage","Audit maintenance"));
            SecurityContextHolder.clearContext(); mvc.perform(get("/packages")).andExpect(redirectedUrl("/maintenance"));
        } finally { settings.updateWebsiteStatus("ACTIVE",""); }
    }
    @Test void bookingThroughVisaPaymentConfirmationAndReviewWorksThroughHttpAndSql() throws Exception {
        submit(customer,"/customer/bookings/create",Map.of("requestToken",UUID.randomUUID().toString(),"packageId",tour.getId().toString(),"travelDate","2030-02-01","numberOfTravelers","1","hotelId",hotel.getId().toString(),"travelerNames","Audit traveler","travelerPassports","P123","travelerDobs","1990-01-01","travelerGenders","Other","travelerNationalities","Test"));
        Booking b=bookingRepo.findByUser_IdOrderByCreatedAtDesc(customer.getId()).get(0);
        assertEquals(1,sql.queryForObject("select count(*) from travelers where booking_id=?",Integer.class,b.getId()));
        submit(customer,"/customer/visas/apply",Map.of("bookingId",b.getId().toString(),"visaType",VisaType.values()[0].name()));
        VisaApplication v=visaRepo.findByBooking_Id(b.getId()).orElseThrow(); SecurityContextHolder.clearContext();
        mvc.perform(multipart("/customer/visas/"+v.getId()+"/documents").file(pdf()).param("documentName","Passport").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf()))
            .andExpect(flash().attributeExists("successMessage"));
        submit(officer,"/staff/visas/"+v.getId()+"/verify-all",Map.of());
        submit(officer,"/staff/visas/"+v.getId()+"/charges",Map.of("visaCharge","30","documentationCharge","10"));
        submit(customer,"/customer/payments/process",Map.of("bookingId",b.getId().toString(),"paymentType","VISA_DOCUMENTATION","amount","40"));
        submit(officer,"/staff/visas/"+v.getId()+"/approve",Map.of());
        submit(customer,"/customer/payments/process",Map.of("bookingId",b.getId().toString(),"paymentType","FULL_PACKAGE","amount","160"));
        assertEquals("CONFIRMED",sql.queryForObject("select booking_status from bookings where id=?",String.class,b.getId()));
        assertEquals(2,sql.queryForObject("select count(*) from payments where booking_id=? and payment_status='PAID'",Integer.class,b.getId()));
        assertTrue(page(customer,"/customer/bookings/"+b.getId()).contains("CONFIRMED"));
        assertTrue(page(customer,"/customer/notifications").contains("Visa approved"));
        clock.instant = b.getTravelDate().plusDays(tour.getDurationDays()+1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        submit(customer,"/customer/reviews",Map.of("bookingId",b.getId().toString(),"rating","5","comment","Audit review"));
        assertEquals(1,sql.queryForObject("select count(*) from reviews where booking_id=?",Integer.class,b.getId()));
    }
    @Test void reproducePermissionCheckboxesPersistWithoutAffectingAuthorization() throws Exception {
        Permission permission=permissions.save(new Permission("AUDIT_PERMISSION_"+UUID.randomUUID(),"Audit","AUDIT"));
        submit(admin,"/admin/roles/"+consultant.getRole().getId()+"/permissions",Map.of("permissionIds",permission.getId().toString()));
        assertEquals(Boolean.TRUE, tx.execute(s->roleRepo.findById(consultant.getRole().getId()).orElseThrow().getPermissions().stream().anyMatch(p->p.getId().equals(permission.getId()))));
        assertEquals(List.of("ROLE_TRAVEL_CONSULTANT"),userDetails.loadUserByUsername(consultant.getEmail()).getAuthorities().stream().map(Object::toString).toList());
        submit(admin,"/admin/roles/"+consultant.getRole().getId()+"/permissions",Map.of());
        assertTrue(userDetails.loadUserByUsername(consultant.getEmail()).getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_TRAVEL_CONSULTANT")));
    }
    @Test void inquiryReplyReachesCustomerNotifications() throws Exception {
        String marker="Audit inquiry "+UUID.randomUUID();
        submit(customer,"/contact",Map.of("submissionKey",UUID.randomUUID().toString(),"senderName","Audit customer","senderEmail",customer.getEmail(),"subject",marker,"message","Audit message"));
        ContactMessage message=inquiries.findAll().stream().filter(i->marker.equals(i.getSubject())).findFirst().orElseThrow();
        long before=notificationRepo.count();
        submit(consultant,"/staff/inquiries/"+message.getId()+"/reply",Map.of("replyText","Audit response"));
        assertEquals("Audit response",inquiries.findById(message.getId()).orElseThrow().getReplyText());
        assertEquals(before+1,notificationRepo.count()); assertTrue(page(customer,"/customer/notifications").contains("Audit response"));
    }
    @Test void rejectInvalidReviewForPendingBooking() throws Exception {
        Booking b=create(false);
        mvc.perform(post("/customer/reviews").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf()).param("bookingId",b.getId().toString()).param("rating","99").param("comment","INVALID_AUDIT_REVIEW")).andExpect(flash().attributeExists("errorMessage"));
        assertEquals(0,sql.queryForObject("select count(*) from reviews where booking_id=?",Integer.class,b.getId()));
        assertFalse(page(null,"/packages").contains("INVALID_AUDIT_REVIEW"));
        assertFalse(page(customer,"/customer/bookings/"+b.getId()).contains("INVALID_AUDIT_REVIEW"));
    }
    @Test void disabledStaffSessionCannotWriteCatalogue() throws Exception {
        tx.executeWithoutResult(s -> userRepo.findById(consultant.getId()).orElseThrow().setPassword(encoder.encode("AuditPass123!")));
        SecurityContextHolder.clearContext();
        var loggedIn=mvc.perform(post("/auth/login").with(csrf()).param("email",consultant.getEmail()).param("password","AuditPass123!"))
            .andExpect(redirectedUrl("/staff/dashboard")).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)loggedIn.getRequest().getSession(false);
        submit(admin,"/admin/users/"+consultant.getId()+"/toggle-status",Map.of());
        assertFalse(userRepo.findById(consultant.getId()).orElseThrow().isActive());
        SecurityContextHolder.clearContext(); String name="Disabled-session-"+UUID.randomUUID();
        mvc.perform(post("/staff/categories/create").session(session).with(csrf()).param("name",name))
            .andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        assertFalse(categoryRepo.findAll().stream().anyMatch(c -> c.getName().equals(name)));
    }
}
