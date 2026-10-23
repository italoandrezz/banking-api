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
    @com.italo.bankingapi.validation.NoNullCharacter
    private String fullName;

    @NotBlank(message = "Email is required.")
    @Email(message = "Email format is invalid.")
    @jakarta.validation.constraints.Size(max = 150, message = "Email must have at most 150 characters.")
    @com.italo.bankingapi.validation.NoNullCharacter
    private String email;

    @NotBlank(message = "Phone is required.")
    @jakarta.validation.constraints.Size(max = 20, message = "Phone must have at most 20 characters.")
    @com.italo.bankingapi.validation.NoNullCharacter
    private String phone;

    @NotNull(message = "Birth date is required.")
    @jakarta.validation.constraints.Past(message = "Birth date must be in the past.")
    @com.italo.bankingapi.validation.SupportedDate
    private LocalDate birthDate;
}