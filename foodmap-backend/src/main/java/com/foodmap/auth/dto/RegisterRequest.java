package com.foodmap.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Email must be a valid address")
    @Size(max = 255)
    String email,

    @NotBlank(message = "Password must not be blank")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    String password,

    @NotBlank(message = "Display name must not be blank")
    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    String displayName

) {}
