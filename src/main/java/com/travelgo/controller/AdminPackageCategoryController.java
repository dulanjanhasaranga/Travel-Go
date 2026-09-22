package com.travelgo.controller;

import com.travelgo.entity.PackageCategory;
import com.travelgo.service.PackageCategoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for Package Category management in Admin panel.
 */
@Controller
@RequestMapping("/staff/categories")
public class AdminPackageCategoryController {

    private final PackageCategoryService categoryService;

    public AdminPackageCategoryController(PackageCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public String listCategories(Model model) {
        model.addAttribute("categories", categoryService.findAll());
        return "staff/categories";
    }

    @PostMapping("/create")
    public String createCategory(@RequestParam("name") String name,
                                @RequestParam(value = "description", required = false) String description,
                                RedirectAttributes redirectAttributes) {
        try {
            PackageCategory cat = new PackageCategory();
            cat.setName(name);
            cat.setDescription(description);
            categoryService.save(cat);
            redirectAttributes.addFlashAttribute("successMessage", "Category created successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", (e instanceof IllegalArgumentException ? e.getMessage() : "The change could not be saved. Check for duplicate names or linked records and try again."));
        }
        return "redirect:/staff/categories";
    }

    @PostMapping("/{id}/edit")
    public String editCategory(@PathVariable("id") Long id,
                              @RequestParam("name") String name,
                              @RequestParam(value = "description", required = false) String description,
                              RedirectAttributes redirectAttributes) {
        try {
            PackageCategory cat = categoryService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Category not found"));
            cat.setName(name);
            cat.setDescription(description);
            categoryService.save(cat);
            redirectAttributes.addFlashAttribute("successMessage", "Category updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", (e instanceof IllegalArgumentException ? e.getMessage() : "The change could not be saved. Check for duplicate names or linked records and try again."));
        }
        return "redirect:/staff/categories";
    }

    @PostMapping("/{id}/delete")
    public String deleteCategory(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            categoryService.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Category deactivated. Existing records are preserved.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", (e instanceof IllegalArgumentException ? e.getMessage() : "The change could not be saved. Check for duplicate names or linked records and try again."));
        }
        return "redirect:/staff/categories";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes attributes) {
        try { categoryService.setActive(id,true); attributes.addFlashAttribute("successMessage","Category activated."); }
        catch (IllegalArgumentException e) { attributes.addFlashAttribute("errorMessage",e.getMessage()); }
        return "redirect:/staff/categories";
    }
}
