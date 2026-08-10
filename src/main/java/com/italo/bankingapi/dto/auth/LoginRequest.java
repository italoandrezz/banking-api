package com.italo.bankingapi.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    @NotBlank(message = "E-mail is required.")
    @Email(message = "E-mail must be valid.")
    private String email;

    @NotBlank(message = "Password is required.")
    private String password;

}
