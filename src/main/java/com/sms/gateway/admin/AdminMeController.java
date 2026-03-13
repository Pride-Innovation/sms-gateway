package com.sms.gateway.admin;

import com.sms.gateway.adminuser.AdminPasswordPolicyService;
import com.sms.gateway.adminuser.AdminUser;
import com.sms.gateway.adminuser.AdminUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminMeController {

    private final AdminUserRepository adminUserRepository;
    private final AdminPasswordPolicyService adminPasswordPolicyService;

    public AdminMeController(AdminUserRepository adminUserRepository,
                             AdminPasswordPolicyService adminPasswordPolicyService) {
        this.adminUserRepository = adminUserRepository;
        this.adminPasswordPolicyService = adminPasswordPolicyService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        AdminUser adminUser = adminUserRepository.findByUsernameIgnoreCase(authentication.getName()).orElse(null);
        AdminPasswordPolicyService.PasswordStatus passwordStatus = adminUser == null
                ? null
                : adminPasswordPolicyService.evaluate(adminUser);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", authentication.getName());
        response.put("roles", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        response.put("passwordChangeRequired", passwordStatus != null && passwordStatus.passwordChangeRequired());
        response.put("passwordExpired", passwordStatus != null && passwordStatus.passwordExpired());
        response.put("passwordExpiresAt", passwordStatus == null ? null : passwordStatus.passwordExpiresAt());
        return response;
    }
}
