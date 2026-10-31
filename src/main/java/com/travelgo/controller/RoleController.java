package com.travelgo.controller;

import com.travelgo.entity.Role;
import com.travelgo.service.PermissionService;
import com.travelgo.service.RoleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Controller for Role Management in Admin panel.
 */
@Controller
@RequestMapping("/admin/roles")
public class RoleController {

    private final RoleService roleService;
    private final PermissionService permissionService;

    public RoleController(RoleService roleService, PermissionService permissionService) {
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public String listRoles(Model model) {
        model.addAttribute("roles", roleService.getAllRoles());
        return "admin/roles";
    }

    @GetMapping("/{id}")
    public String viewRole(@PathVariable("id") Long id, Model model, RedirectAttributes redirectAttributes) {
        return roleService.getRoleById(id).map(role -> {
            model.addAttribute("role", role);
            model.addAttribute("allPermissions", permissionService.getAllPermissions());
            model.addAttribute("modules", permissionService.getAllModules());
            model.addAttribute("missingBookingPermissions", roleService.missingConsultantBookingPermissions(role));
            return "admin/role-detail";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("errorMessage", "Role not found.");
            return "redirect:/admin/roles";
        });
    }

    @PostMapping("/{id}/booking-access")
    public String enableBookingAccess(@PathVariable("id") Long id,
                                      @RequestParam(value = "confirmed", defaultValue = "false") boolean confirmed,
                                      RedirectAttributes redirectAttributes) {
        try {
            boolean changed = roleService.enableConsultantBookingAccess(id, confirmed);
            redirectAttributes.addFlashAttribute("successMessage", changed
                    ? "Booking access enabled. Affected consultants must sign in again."
                    : "This role already has booking access.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/admin/roles/" + id;
    }

    @PostMapping("/{id}/permissions")
    public String updateRolePermissions(@PathVariable("id") Long id,
                                        @RequestParam(value = "permissionIds", required = false) List<Long> permissionIds,
                                        RedirectAttributes redirectAttributes) {
        try {
            Set<Long> permSet = (permissionIds != null) ? new HashSet<>(permissionIds) : new HashSet<>();
            roleService.updateRolePermissions(id, permSet);
            redirectAttributes.addFlashAttribute("successMessage", "Permissions updated.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/admin/roles/" + id;
    }
}
