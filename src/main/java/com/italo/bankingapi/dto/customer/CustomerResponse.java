package com.italo.bankingapi.dto.customer;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerResponse {

    private UUID id;
    private String fullName;
    private String cpf;
    private String email;
    private String phone;
    private LocalDate birthDate;
    private LocalDateTime createdAt;

}
