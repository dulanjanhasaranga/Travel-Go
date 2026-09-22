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

/**
 * Controller for Staff Management in Admin panel.
 */
@Controller
@RequestMapping("/admin/staff")
public class StaffManagementController {

    private final UserService userService;
    private final RoleService roleService;

    public StaffManagementController(UserService userService, RoleService roleService) {
        this.userService = userService;
        this.roleService = roleService;
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
            userService.createStaffAccount(
                    request.getName(),
                    request.getEmail(),
                    request.getPassword(),
                    request.getPhone(),
                    request.getAddress(),
                    request.getRoleName()
            );
            redirectAttributes.addFlashAttribute("successMessage", "Staff account created successfully.");
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
            userService.updateStaff(id, name, phone, address, roleName);
            redirectAttributes.addFlashAttribute("successMessage", "Staff account updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update staff: " + e.getMessage());
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            userService.toggleUserStatus(id);
            redirectAttributes.addFlashAttribute("successMessage", "Staff account status toggled.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update status: " + e.getMessage());
        }
        return "redirect:/admin/staff";
    }
}
