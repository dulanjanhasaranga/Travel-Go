package com.travelgo.config;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.CustomUserDetailsService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request filter that validates the authenticated session is still valid.
 * Checks: account active, demo mode, role match, password unchanged,
 * and permission hash unchanged (so admin permission checkbox edits
 * take effect for already-logged-in users).
 */
public class AccountSessionFilter extends OncePerRequestFilter {
 private final UserRepository users;
 private final DemoMode demoMode;
 public AccountSessionFilter(UserRepository users,DemoMode demoMode){this.users=users;this.demoMode=demoMode;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
  var auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth!=null&&auth.isAuthenticated()&&!(auth instanceof AnonymousAuthenticationToken)) {
   var user=users.findByEmailIgnoreCase(auth.getName());var session=request.getSession(false);
   String baseline=session==null?null:(String)session.getAttribute("accountPasswordVersion");
   boolean allowed=user.isPresent()&&user.get().isActive()&&(!user.get().isDemoAccount()||demoMode.isEnabled())&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+com.travelgo.security.RoleNames.canonical(user.get().getRole().getRoleName())))&&(baseline==null||baseline.equals(user.get().getPassword()));
   // Check if role permissions have changed since session was created
   if(allowed&&session!=null){
    String storedPermHash=(String)session.getAttribute("permissionHash");
    String currentPermHash=CustomUserDetailsService.permissionHash(user.get().getRole().getPermissions());
    if(storedPermHash!=null&&!storedPermHash.equals(currentPermHash)){allowed=false;}
   }
   if(!allowed){SecurityContextHolder.clearContext();if(session!=null)session.invalidate();response.sendRedirect("/auth/login?sessionChanged=true");return;}
   if(session!=null&&session.getAttribute("permissionHash")==null){
    session.setAttribute("permissionHash",CustomUserDetailsService.permissionHash(user.get().getRole().getPermissions()));
   }
   if(baseline==null)request.getSession().setAttribute("accountPasswordVersion",user.get().getPassword());
  }
  chain.doFilter(request,response);
 }
}
