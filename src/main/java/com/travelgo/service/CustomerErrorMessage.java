package com.travelgo.service;
/** Keep database/provider diagnostics out of customer flash messages. */
public final class CustomerErrorMessage {
 private CustomerErrorMessage(){}
 public static String from(Exception error){
  if(error.getClass()==IllegalArgumentException.class||error.getClass()==IllegalStateException.class)return error.getMessage();
  if(error instanceof org.springframework.security.access.AccessDeniedException)return "Access denied.";
  return "We could not complete this request. Please try again or contact the travel team.";
 }
}
