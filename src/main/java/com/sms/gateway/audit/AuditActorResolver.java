package com.sms.gateway.audit;

import com.sms.gateway.security.ApiClientPrincipal;
import com.sms.gateway.security.UserPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuditActorResolver {

    private AuditActorResolver() {
    }

    public static AuditActor resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return new AuditActor(AuditActorType.SYSTEM, "system");
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return new AuditActor(AuditActorType.ANONYMOUS, "anonymous");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return new AuditActor(AuditActorType.ADMIN_USER, userPrincipal.getUsername());
        }
        if (principal instanceof ApiClientPrincipal apiClientPrincipal) {
            return new AuditActor(AuditActorType.API_CLIENT, String.valueOf(apiClientPrincipal.id()));
        }
        if (principal instanceof String p && !p.isBlank()) {
            return new AuditActor(AuditActorType.ADMIN_USER, p);
        }

        return new AuditActor(AuditActorType.SYSTEM, "system");
    }
}
