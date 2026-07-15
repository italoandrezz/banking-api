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
    private String email;

    @NotBlank(message = "Password is required.")
    private String password;

    @NotBlank(message = "Phone is required.")
    private String phone;

    @NotNull(message = "Birth date is required.")
    private LocalDate birthDate;
}
