package com.sms.gateway.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class ApiClientAuthenticationToken extends AbstractAuthenticationToken {

    private final ApiClientPrincipal principal;

    public ApiClientAuthenticationToken(ApiClientPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
