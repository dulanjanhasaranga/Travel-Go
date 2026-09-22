package com.travelgo.controller;

import com.travelgo.service.PermissionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller for Permission listing in Admin panel.
 */
@Controller
@RequestMapping("/admin/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public String listPermissions(Model model) {
        model.addAttribute("permissions", permissionService.getAllPermissions());
        model.addAttribute("modules", permissionService.getAllModules());
        return "admin/permissions";
    }
}
