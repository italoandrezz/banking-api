package com.italo.bankingapi.dto.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCustomerRequest {

    @NotBlank(message = "Full name is required.")
    @Size(max = 120, message = "Full name must have a maximum of 120 characters.")
    private String fullName;

    @NotBlank(message = "Email is required.")
    @Email(message = "Email format is invalid.")
    private String email;

    @NotBlank(message = "Phone is required.")
    private String phone;

    @NotNull(message = "Birth date is required.")
    private LocalDate birthDate;
}