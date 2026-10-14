package com.italo.bankingapi.dto.customer;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCustomerRequest {

    @NotBlank(message = "Full name is required.")
    @Size(max = 120, message = "Full name must have a maximum of 120 characters.")
    private String fullName;

    @NotBlank(message = "CPF is required.")
    @Pattern(regexp = "\\d{11}", message = "CPF must contain exactly 11 digits.")
    private String cpf;

    @NotBlank(message = "Email is required.")
    @Email(message = "Email format is invalid.")
    @jakarta.validation.constraints.Size(max = 150, message = "Email must have at most 150 characters.")
    private String email;

    @NotBlank(message = "Password is required.")
    @com.italo.bankingapi.validation.PasswordLength(minCharacters = 8,
            message = "Password must have at least 8 Unicode characters and at most 72 UTF-8 bytes.")
    private String password;

    @NotBlank(message = "Phone is required.")
    @jakarta.validation.constraints.Size(max = 20, message = "Phone must have at most 20 characters.")
    private String phone;

    @NotNull(message = "Birth date is required.")
    @jakarta.validation.constraints.Past(message = "Birth date must be in the past.")
    private LocalDate birthDate;
}
