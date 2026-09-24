package com.travelgo.controller;
import com.travelgo.service.ContactMessageService;
import com.travelgo.enums.ContactMessageStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
@Controller @RequestMapping({"/staff/inquiries","/admin/inquiries"})
public class StaffInquiryController {
 private final ContactMessageService service;
 public StaffInquiryController(ContactMessageService service){this.service=service;}
 private String base(jakarta.servlet.http.HttpServletRequest request){return request.getRequestURI().startsWith("/admin/")?"/admin/inquiries":"/staff/inquiries";}
 @GetMapping public String list(@RequestParam(value="search",required=false)String search,@RequestParam(value="status",required=false)ContactMessageStatus status,Model model,jakarta.servlet.http.HttpServletRequest request){
  service.requireManager();String q=search==null?"":search.trim().toLowerCase(java.util.Locale.ROOT);
  model.addAttribute("inquiries",service.findAll().stream().filter(i->status==null||i.getStatus()==status).filter(i->q.isEmpty()||(i.getReference()+" "+i.getSubject()+" "+i.getSenderName()+" "+i.getSenderEmail()).toLowerCase(java.util.Locale.ROOT).contains(q)).sorted(java.util.Comparator.comparing(com.travelgo.entity.ContactMessage::getCreatedAt).reversed()).toList());
  model.addAttribute("base",base(request));model.addAttribute("search",search);model.addAttribute("selectedStatus",status);model.addAttribute("statuses",ContactMessageStatus.values());return "staff/inquiry-list";
 }
 @GetMapping("/{id}") public String detail(@PathVariable Long id,Model model,jakarta.servlet.http.HttpServletRequest request){
  service.requireManager();model.addAttribute("inquiry",service.findById(id).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)));model.addAttribute("base",base(request));model.addAttribute("statuses",ContactMessageStatus.values());return "staff/inquiry-detail";
 }
 @PostMapping("/{id}/status") public String status(@PathVariable Long id,@RequestParam ContactMessageStatus status,@RequestParam(required=false)String internalNotes,@RequestParam(required=false)Long version,RedirectAttributes flash,jakarta.servlet.http.HttpServletRequest request){
  try{service.updateStatus(id,status,internalNotes,version);flash.addFlashAttribute("successMessage","Inquiry updated.");}catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}
  return "redirect:"+base(request)+"/"+id;
 }
 @PostMapping("/{id}/reply") public String reply(@PathVariable Long id,@RequestParam String replyText,RedirectAttributes flash,jakarta.servlet.http.HttpServletRequest request){
  try{service.replyToMessage(id,replyText);flash.addFlashAttribute("successMessage","Reply sent and emailed to the customer.");}catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}
  return "redirect:"+base(request);
 }
}

