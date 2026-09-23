package com.travelgo.service;

import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public CustomOAuth2UserService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> attributes = oAuth2User.getAttributes();
        String email = (String) attributes.get("email");
        String name = (String) attributes.get("name");

        if (email == null) {
            throw new OAuth2AuthenticationException("Email not found from OAuth2 provider");
        }

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setPassword(UUID.randomUUID().toString()); // Random dummy password
            user.setActive(true);
            user.setDemoAccount(false);
            Role customerRole = roleRepository.findByRoleName("CUSTOMER")
                    .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));
            user.setRole(customerRole);
            userRepository.save(user);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + com.travelgo.security.RoleNames.canonical(user.getRole().getRoleName())));
        if (user.getRole().getPermissions() != null) {
            for (com.travelgo.entity.Permission p : user.getRole().getPermissions()) {
                authorities.add(new SimpleGrantedAuthority("PERM_" + p.getPermissionName()));
            }
        }

        return new CustomOAuth2User(authorities, attributes, "email");
    }

    public static class CustomOAuth2User extends DefaultOAuth2User {
        public CustomOAuth2User(List<GrantedAuthority> authorities, Map<String, Object> attributes, String nameAttributeKey) {
            super(authorities, attributes, nameAttributeKey);
        }
        
        @Override
        public String getName() {
            return (String) getAttributes().get("email");
        }
    }
}
