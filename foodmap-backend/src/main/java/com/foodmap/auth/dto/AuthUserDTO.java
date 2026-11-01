package com.foodmap.auth.dto;

import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;

public record AuthUserDTO(
    Long id,
    String email,
    String displayName,
    String avatarUrl,
    Role role
) {

    public static AuthUserDTO from(User user) {
        return new AuthUserDTO(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            user.getRole()
        );
    }
}
