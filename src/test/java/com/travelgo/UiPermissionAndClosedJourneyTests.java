package com.travelgo;

import com.travelgo.entity.Booking;
import com.travelgo.entity.SupplierHold;
import com.travelgo.enums.BookingStatus;
import com.travelgo.enums.VisaStatus;
import com.travelgo.repository.SupplierHoldRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Render actual secured pages using only the inherited isolated H2 fixtures. No business POSTs. */
class UiPermissionAndClosedJourneyTests extends WorkflowIntegrationTests {
    private String page(String path, com.travelgo.entity.User actor, String... authorities) throws Exception {
        SecurityContextHolder.clearContext();
        return mvc.perform(get(path).with(user(actor.getEmail()).authorities(
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private boolean tag(String html, String element, String attributePattern) {
        return Pattern.compile("<" + element + "\\b[^>]*" + attributePattern + "[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html).find();
    }
    private void setStatus(Booking booking, BookingStatus next) {
        tx.executeWithoutResult(s -> bookingRepo.findById(booking.getId()).orElseThrow().setBookingStatus(next));
    }

    @Test void completedAndDepartedBookingsHaveCancellationHistoryWithoutAnUnavailableRequestForm() throws Exception {
        Booking booking = create(false);
        setStatus(booking, BookingStatus.COMPLETED);
        String path = "/customer/bookings/" + booking.getId() + "/cancellation";
        String completed = page(path, customer, "ROLE_CUSTOMER");
        assertFalse(tag(completed, "form", "action=\"" + path + "\""));
        assertTrue(completed.contains("New cancellation requests are unavailable"));
        setStatus(booking, BookingStatus.CONFIRMED);
        clock.instant = Instant.parse("2030-02-01T00:00:00Z");
        String departed = page(path, customer, "ROLE_CUSTOMER");
        assertFalse(tag(departed, "form", "action=\"" + path + "\""));
        clock.instant = Instant.parse("2030-01-31T23:59:59Z");
        assertTrue(tag(page(path, customer, "ROLE_CUSTOMER"), "form", "action=\"" + path + "\""));
    }

    @Test void completedSupplierRecordIsReadOnlyEvenWithoutConfirmationEvidence() throws Exception {
        Booking booking = create(false);
        tx.executeWithoutResult(s -> {
            SupplierHold hold = context.getBean(SupplierHoldRepository.class).findByBooking_Id(booking.getId()).orElseThrow();
            hold.setConfirmed(false); hold.setSupplierName(null); hold.setConfirmationReference(null); hold.setServiceNotes(null);
        });
        setStatus(booking, BookingStatus.COMPLETED);
        String path = "/staff/bookings/" + booking.getId() + "/suppliers";
        String html = page(path, consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_BOOKING_VIEW", "PERM_BOOKING_MANAGE");
        assertFalse(tag(html, "form", "action=\"" + path + "\""));
        assertTrue(html.contains("This booking is closed. Supplier details are available for reference"));
        setStatus(booking, BookingStatus.PROCESSING);
        assertTrue(tag(page(path, consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_BOOKING_MANAGE"), "form", "action=\"" + path + "\""));
        assertFalse(tag(page(path, consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_BOOKING_VIEW"), "form", "action=\"" + path + "\""));
    }

    @Test void viewOnlyOfficersKeepVisaRecordsButCannotOpenDecisionOrFeeActions() throws Exception {
        Booking booking = create(false);
        var visa = apply(booking);
        tx.executeWithoutResult(s -> visaRepo.findById(visa.getId()).orElseThrow().setStatus(VisaStatus.PROCESSING));
        String html = page("/staff/visas", officer, "ROLE_VISA_OFFICER", "PERM_VISA_VIEW");
        assertTrue(html.contains("Test tour"));
        assertTrue(tag(html, "button", "data-target=\"docsModal" + visa.getId() + "\""));
        assertFalse(tag(html, "button", "data-target=\"approveVisaModal" + visa.getId() + "\""));
        assertFalse(tag(html, "form", "action=\"/staff/visas/" + visa.getId() + "/charges\""));
        String manager = page("/staff/visas", officer, "ROLE_VISA_OFFICER", "PERM_VISA_MANAGE");
        assertTrue(tag(manager, "button", "data-target=\"approveVisaModal" + visa.getId() + "\""));
        assertTrue(tag(manager, "form", "action=\"/staff/visas/" + visa.getId() + "/approve\""));
    }

    @Test void viewOnlyPaymentPageHasLedgerWithoutPaymentMutationControls() throws Exception {
        String html = page("/staff/payments", officer, "ROLE_VISA_OFFICER", "PERM_PAYMENT_VIEW");
        assertTrue(html.contains("Payment history"));
        assertFalse(tag(html, "button", "data-ui-click=\"openRecordModal\""));
        assertFalse(tag(html, "form", "action=\"/staff/payments/record\""));
        assertFalse(tag(html, "form", "id=\"updateForm\""));
        String manager = page("/staff/payments", officer, "ROLE_VISA_OFFICER", "PERM_PAYMENT_MANAGE");
        assertTrue(tag(manager, "form", "action=\"/staff/payments/record\""));
        assertTrue(tag(manager, "form", "id=\"updateForm\""));
    }

    @Test void destinationViewersKeepCatalogueRowsWithoutAddEditOrDeactivateControls() throws Exception {
        for (String path : java.util.List.of("/staff/destinations", "/staff/hotels", "/staff/transports", "/staff/categories")) {
            String html = page(path, consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_DESTINATION_VIEW");
            assertTrue(html.contains("data-table"), path);
            assertFalse(tag(html, "button", "data-ui-click=\"open(?:Add|Edit)"), path);
            assertFalse(tag(html, "form", "(?=[^>]*method=\"post\")(?=[^>]*action=\"/staff/(?:destinations|hotels|transports|categories))"), path);
            String manager = page(path, consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_DESTINATION_MANAGE");
            assertTrue(tag(manager, "button", "data-ui-click=\"openAdd"), path);
        }
    }

    @Test void packageViewersCanInspectDeparturesAndItineraryWithoutEditing() throws Exception {
        String html = page("/staff/packages", consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_PACKAGE_VIEW");
        assertTrue(html.contains("Test tour"));
        assertFalse(tag(html, "button", "data-ui-click=\"openSlidePanel\""));
        assertFalse(tag(html, "button", "data-ui-click=\"loadPackageEditor\""));
        assertTrue(tag(html, "button", "data-ui-click=\"openManageDeparturesModal\""));
        assertTrue(tag(html, "input", "id=\"depPackageId\""));
        assertFalse(tag(html, "form", "id=\"addDepartureForm\""));
        String content = page("/staff/packages/" + tour.getId() + "/content", consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_PACKAGE_VIEW");
        assertTrue(tag(content, "fieldset", "class=\"permission-readonly-fields\"[^>]*disabled"));
        assertFalse(content.contains(">Save package content</button>"));
        String manager = page("/staff/packages", consultant, "ROLE_TRAVEL_CONSULTANT", "PERM_PACKAGE_MANAGE");
        assertTrue(tag(manager, "form", "id=\"addDepartureForm\""));
    }

    @Test void roleAndSettingsViewersRetainFieldValuesWithoutSaveButtons() throws Exception {
        String role = page("/admin/roles/" + consultant.getRole().getId(), admin, "ROLE_ADMIN", "PERM_ROLE_VIEW");
        assertTrue(role.contains("Permission settings"));
        assertTrue(tag(role, "fieldset", "class=\"permission-readonly-fields\"[^>]*disabled"));
        assertFalse(role.contains("Save permissions"));
        String manager = page("/admin/roles/" + consultant.getRole().getId(), admin, "ROLE_ADMIN", "PERM_ROLE_MANAGE");
        assertTrue(manager.contains("Save permissions"));
        String settings = page("/admin/settings", admin, "ROLE_ADMIN", "PERM_SYSTEM_SETTINGS_VIEW");
        assertTrue(tag(settings, "input", "id=\"company-name\""));
        assertTrue(tag(settings, "fieldset", "class=\"permission-readonly-fields\"[^>]*disabled"));
        assertFalse(settings.contains("Save company profile"));
    }
}
