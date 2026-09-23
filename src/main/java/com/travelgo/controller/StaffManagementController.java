package com.travelgo.controller;

import com.travelgo.dto.StaffCreateRequest;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.service.RoleService;
import com.travelgo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import com.travelgo.service.ApprovalService;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import java.util.HashMap;

/**
 * Controller for Staff Management in Admin panel.
 */
@Controller
@RequestMapping("/admin/staff")
public class StaffManagementController {

    private final UserService userService;
    private final RoleService roleService;
    private final ApprovalService approvalService;

    public StaffManagementController(UserService userService, RoleService roleService, ApprovalService approvalService) {
        this.userService = userService;
        this.roleService = roleService;
        this.approvalService = approvalService;
    }

    @GetMapping
    public String listStaff(Model model) {
        model.addAttribute("staffList", userService.getAllStaff());
        return "admin/staff";
    }

    @GetMapping("/new")
    public String newStaffForm(Model model) {
        model.addAttribute("staffRequest", new StaffCreateRequest());
        model.addAttribute("roles", roleService.getStaffRoles());
        return "admin/staff-form";
    }

    @PostMapping("/create")
    public String createStaff(@Valid @ModelAttribute("staffRequest") StaffCreateRequest request,
                             BindingResult result,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            model.addAttribute("roles", roleService.getStaffRoles());
            return "admin/staff-form";
        }
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", request.getName());
            payload.put("email", request.getEmail());
            payload.put("password", request.getPassword());
            payload.put("phone", request.getPhone());
            payload.put("address", request.getAddress());
            payload.put("roleName", request.getRoleName());
            
            approvalService.submitRequest("STAFF", null, "CREATE", payload, currentUserEmail);
            
            redirectAttributes.addFlashAttribute("successMessage", "Staff creation request submitted for approval.");
            return "redirect:/admin/staff";
        } catch (Exception e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("roles", roleService.getStaffRoles());
            return "admin/staff-form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editStaffForm(@PathVariable("id") Long id, Model model, RedirectAttributes redirectAttributes) {
        return userService.getUserById(id).map(user -> {
            model.addAttribute("staffUser", user);
            model.addAttribute("roles", roleService.getStaffRoles());
            return "admin/staff-edit";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("errorMessage", "Staff member not found.");
            return "redirect:/admin/staff";
        });
    }

    @PostMapping("/{id}/update")
    public String updateStaff(@PathVariable("id") Long id,
                             @RequestParam("name") String name,
                             @RequestParam("phone") String phone,
                             @RequestParam("address") String address,
                             @RequestParam("roleName") String roleName,
                             RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", name);
            payload.put("phone", phone);
            payload.put("address", address);
            payload.put("roleName", roleName);
            
            approvalService.submitRequest("STAFF", id, "UPDATE", payload, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Staff update request submitted for approval.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to submit request: " + e.getMessage());
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            approvalService.submitRequest("STAFF", id, "TOGGLE_STATUS", new HashMap<>(), currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Status toggle request submitted for approval.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to submit request: " + e.getMessage());
        }
        return "redirect:/admin/staff";
    }
}
