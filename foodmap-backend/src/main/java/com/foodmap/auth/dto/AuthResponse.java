package com.foodmap.auth.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long   expiresInSeconds,
    AuthUserDTO user
) {

    public static AuthResponse of(String accessToken, String refreshToken,
                                  long accessExpirationMs, AuthUserDTO user) {
        return new AuthResponse(
            accessToken,
            refreshToken,
            "Bearer",
            accessExpirationMs / 1000,
            user
        );
    }
}
