package com.travelgo.controller;

import com.travelgo.dto.VisaChecklistRequest;
import com.travelgo.entity.VisaChecklist;
import com.travelgo.enums.VisaType;
import com.travelgo.service.VisaChecklistService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/staff/visas/checklists")
public class StaffVisaChecklistController {
    private final VisaChecklistService service;
    public StaffVisaChecklistController(VisaChecklistService service) { this.service=service; }
    @GetMapping public String list(Model model) {
        model.addAttribute("checklists",service.findAll()); return "staff/visa-checklists";
    }
    private void options(Model model) { model.addAttribute("destinations",service.activeDestinations()); model.addAttribute("visaTypes",VisaType.values()); }
    @GetMapping("/new") public String create(Model model) {
        model.addAttribute("request",new VisaChecklistRequest(null,VisaType.VISIT,null,null,null,null,null,null,null,null));
        options(model); return "staff/visa-checklist-form";
    }
    @GetMapping("/{id}/edit") public String edit(@PathVariable Long id,Model model) {
        var c=service.findForOfficer(id);
        model.addAttribute("checklist",c); model.addAttribute("request",request(c)); options(model); return "staff/visa-checklist-form";
    }
    private VisaChecklistRequest request(VisaChecklist c) {
        return new VisaChecklistRequest(c.getDestination().getId(),c.getVisaType(),c.getSourceUrl(),c.getSourceReviewedOn(),c.getMinimumLeadDays(),
                c.getEstimatedProcessingMinDays(),c.getEstimatedProcessingMaxDays(),c.getGuidance(),
                c.getItems().stream().filter(i->i.isRequired()).map(i->i.getLabel()).collect(Collectors.joining("\n")),
                c.getItems().stream().filter(i->!i.isRequired()).map(i->i.getLabel()).collect(Collectors.joining("\n")));
    }
    @PostMapping public String save(@Valid @ModelAttribute("request") VisaChecklistRequest request,BindingResult errors,Model model,RedirectAttributes flash) {
        if (errors.hasErrors()) { options(model); return "staff/visa-checklist-form"; }
        try { var c=service.createDraft(request); flash.addFlashAttribute("successMessage","Draft revision "+c.getRevision()+" saved. Review its source and requirements before publishing."); return "redirect:/staff/visas/checklists"; }
        catch (IllegalArgumentException|IllegalStateException e) { model.addAttribute("errorMessage",e.getMessage()); options(model); return "staff/visa-checklist-form"; }
    }
    @PostMapping("/{id}") public String update(@PathVariable Long id,@RequestParam Long lockVersion,
            @Valid @ModelAttribute("request") VisaChecklistRequest request,BindingResult errors,Model model,RedirectAttributes flash) {
        model.addAttribute("checklist",service.findForOfficer(id));
        if (errors.hasErrors()) { options(model); return "staff/visa-checklist-form"; }
        try { service.updateDraft(id,lockVersion,request); flash.addFlashAttribute("successMessage","Checklist draft updated."); return "redirect:/staff/visas/checklists"; }
        catch (IllegalArgumentException|IllegalStateException e) { model.addAttribute("errorMessage",e.getMessage()); options(model); return "staff/visa-checklist-form"; }
    }
    @PostMapping("/{id}/publish") public String publish(@PathVariable Long id,@RequestParam Long lockVersion,RedirectAttributes flash) {
        try { service.publish(id,lockVersion); flash.addFlashAttribute("successMessage","Checklist published for new applications. Existing applications keep their assigned checklist."); }
        catch (IllegalArgumentException|IllegalStateException e) { flash.addFlashAttribute("errorMessage",e.getMessage()); } return "redirect:/staff/visas/checklists";
    }
    @PostMapping("/{id}/retire") public String retire(@PathVariable Long id,@RequestParam Long lockVersion,RedirectAttributes flash) {
        try { service.retire(id,lockVersion); flash.addFlashAttribute("successMessage","Checklist retired. Existing applications keep their assigned requirements."); }
        catch (IllegalArgumentException|IllegalStateException e) { flash.addFlashAttribute("errorMessage",e.getMessage()); } return "redirect:/staff/visas/checklists";
    }
    @PostMapping("/applications/{visaId}/attach") public String attach(@PathVariable Long visaId,@RequestParam Long checklistId,RedirectAttributes flash) {
        try { service.attachPublished(visaId,checklistId); flash.addFlashAttribute("successMessage","Checklist assigned and customer notified. Match existing documents to their requirements before verification."); }
        catch (IllegalArgumentException|IllegalStateException e) { flash.addFlashAttribute("errorMessage",e.getMessage()); } return "redirect:/staff/visas";
    }
    @PostMapping("/documents/{documentId}/assign") public String assign(@PathVariable Long documentId,@RequestParam Long checklistItemId,RedirectAttributes flash) {
        try { service.assignExistingDocument(documentId,checklistItemId); flash.addFlashAttribute("successMessage","Document matched to the selected requirement."); }
        catch (IllegalArgumentException|IllegalStateException e) { flash.addFlashAttribute("errorMessage",e.getMessage()); } return "redirect:/staff/visas";
    }
}
