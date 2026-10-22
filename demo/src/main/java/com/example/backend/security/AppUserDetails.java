package com.example.backend.security;

import com.example.backend.model.Role;
import com.example.backend.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * "Người đang đăng nhập" trong Spring Security.
 * Controller lấy ra bằng: @AuthenticationPrincipal AppUserDetails principal
 */
public class AppUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String fullName;
    private final String phone;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    private AppUserDetails(User u) {
        this.id = u.getUserId();
        this.email = u.getEmail();
        this.fullName = u.getFullName();
        this.phone = u.getPhone();
        this.passwordHash = u.getPasswordHash();
        this.role = u.getRole();
        this.enabled = u.isEnabled();
    }

    public static AppUserDetails from(User user) {
        return new AppUserDetails(user);
    }

    public Long getId() { return id; }

    public String getEmail() { return email; }

    public String getFullName() { return fullName; }

    public String getPhone() { return phone; }

    public Role getRole() { return role; }

    public boolean isAdmin() { return role == Role.ADMIN; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return passwordHash; }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return enabled; }
}
