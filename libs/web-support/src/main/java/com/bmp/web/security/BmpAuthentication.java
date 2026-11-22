package com.bmp.web.security;

import java.io.Serial;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** An authenticated bearer token together with the {@link BmpPrincipal} derived from it. */
public final class BmpAuthentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient BmpPrincipal principal;

    public BmpAuthentication(Jwt jwt, BmpPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(jwt, principal, jwt, authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    public BmpPrincipal bmpPrincipal() {
        return principal;
    }

    @Override
    public java.util.Map<String, Object> getTokenAttributes() {
        return getToken().getClaims();
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }
}
