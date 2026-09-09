package com.ojt.board.auth.oauth;

import com.ojt.board.auth.BoardPrincipal;
import com.ojt.board.user.User;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Keeps the validated OIDC principal in the server session and exposes our local account identity. */
public final class BoardOidcUser implements OidcUser, BoardPrincipal {

    private final Long id;
    private final String email;
    private final String nickname;
    private final List<GrantedAuthority> authorities;
    private final OidcUser delegate;

    public BoardOidcUser(User user, OidcUser delegate) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.nickname = user.getNickname();
        // Application roles come only from the local account, never provider claims or scopes.
        this.authorities = List.copyOf(user.getAuthorities());
        this.delegate = delegate;
    }

    @Override public Long getId() { return id; }
    @Override public String getEmail() { return email; }
    @Override public String getNickname() { return nickname; }
    @Override public String getName() { return id.toString(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
}
