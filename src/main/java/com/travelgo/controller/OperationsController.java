package com.travelgo.controller;

import com.travelgo.service.AdminAccess;
import com.travelgo.service.OperationsStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.Map;

@Controller
public class OperationsController {
    private final OperationsStatusService status;
    private final AdminAccess access;
    private final com.travelgo.service.TravelPolicyService policies;
    private final com.travelgo.service.SupplierReservationService suppliers;
    private final boolean publishedPolicyRequired;
    public OperationsController(OperationsStatusService status, AdminAccess access, com.travelgo.service.TravelPolicyService policies,
                                com.travelgo.service.SupplierReservationService suppliers,
                                @org.springframework.beans.factory.annotation.Value("${travelgo.bookings.require-published-policy:false}") boolean publishedPolicyRequired) {
        this.status=status; this.access=access; this.policies=policies; this.suppliers=suppliers; this.publishedPolicyRequired=publishedPolicyRequired;
    }

    /** Only an aggregate status is public: no hostnames, versions, paths, counts or configuration details. */
    @GetMapping("/healthz")
    @ResponseBody
    public ResponseEntity<Map<String,String>> health() {
        boolean healthy=status.dependencies().healthy();
        return ResponseEntity.status(healthy?200:503).cacheControl(CacheControl.noStore())
                .body(Map.of("status",healthy?"UP":"DOWN"));
    }

    @GetMapping("/admin/operations")
    @PreAuthorize("hasRole('ADMIN') and hasAnyAuthority('PERM_SYSTEM_SETTINGS_VIEW','PERM_SYSTEM_SETTINGS_MANAGE')")
    public String operations(Model model) {
        access.requireAdministrator("SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_MANAGE");
        var snapshot=status.snapshot();
        model.addAttribute("operations",snapshot);
        boolean policyAvailable=false, policyPublished=false;
        if (snapshot.dependencies().database()) {
            try { policyPublished=policies.current().isPresent(); policyAvailable=true; }
            catch (RuntimeException unavailable) { /* A release check must not echo private schema errors. */ }
        }
        model.addAttribute("policyCheckAvailable",policyAvailable);
        model.addAttribute("policyPublished",policyPublished);
        model.addAttribute("supplierConfirmationRequired",suppliers.isRequired());
        model.addAttribute("publishedPolicyRequired",publishedPolicyRequired);
        return "admin/operations";
    }
}
