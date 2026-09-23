package com.travelgo.controller;

import com.travelgo.entity.User;
import com.travelgo.service.RoleService;
import com.travelgo.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import com.travelgo.service.ApprovalService;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import java.util.HashMap;

/**
 * Controller for User Management in Admin panel.
 */
@Controller
@RequestMapping("/admin/users")
public class UserManagementController {

    private final UserService userService;
    private final RoleService roleService;
    private final ApprovalService approvalService;

    public UserManagementController(UserService userService, RoleService roleService, ApprovalService approvalService) {
        this.userService = userService;
        this.roleService = roleService;
        this.approvalService = approvalService;
    }

    @GetMapping
    public String listUsers(@RequestParam(value = "search", required = false) String search, Model model) {
        List<User> users;
        if (search != null && !search.trim().isEmpty()) {
            users = userService.searchUsers(search.trim());
        } else {
            users = userService.getAllUsers();
        }
        model.addAttribute("users", users);
        model.addAttribute("search", search);
        return "admin/users";
    }

    @GetMapping("/{id}")
    public String viewUser(@PathVariable("id") Long id, Model model, RedirectAttributes redirectAttributes) {
        return userService.getUserById(id).map(user -> {
            model.addAttribute("user", user);
            model.addAttribute("roles", roleService.getAllRoles());
            return "admin/user-detail";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("errorMessage", "User not found with ID: " + id);
            return "redirect:/admin/users";
        });
    }

    @PostMapping("/{id}/update")
    public String updateUser(@PathVariable("id") Long id,
                             @RequestParam("name") String name,
                             @RequestParam("phone") String phone,
                             @RequestParam("address") String address,
                             RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", name);
            payload.put("phone", phone);
            payload.put("address", address);
            
            approvalService.submitRequest("USER", id, "UPDATE", payload, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "User update request submitted for approval.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to submit request: " + e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            approvalService.submitRequest("USER", id, "TOGGLE_STATUS", new HashMap<>(), currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Status toggle request submitted for approval.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to submit request: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/assign-role")
    public String assignRole(@PathVariable("id") Long id,
                             @RequestParam("roleId") Long roleId,
                             RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            Map<String, Object> payload = new HashMap<>();
            payload.put("roleId", roleId);
            
            approvalService.submitRequest("USER", id, "ASSIGN_ROLE", payload, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Role assignment request submitted for approval.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to submit request: " + e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }
}
