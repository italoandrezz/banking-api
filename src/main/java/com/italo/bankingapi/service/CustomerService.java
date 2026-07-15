package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.customer.CreateCustomerRequest;
import com.italo.bankingapi.dto.customer.CustomerResponse;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        if (customerRepository.existsByCpf(request.getCpf())) {
            throw new ConflictException("CPF already registered.");
        }

        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("E-mail already registered.");
        }

        Customer customer = Customer.builder()
                .fullName(request.getFullName())
                .cpf(request.getCpf())
                .email(request.getEmail())
                .password(request.getPassword())
                .phone(request.getPhone())
                .birthDate(request.getBirthDate())
                .createdAt(LocalDateTime.now())
                .build();

        Customer savedCustomer = customerRepository.save(customer);

        return CustomerResponse.builder()
                .id(savedCustomer.getId())
                .fullName(savedCustomer.getFullName())
                .cpf(savedCustomer.getCpf())
                .email(savedCustomer.getEmail())
                .phone(savedCustomer.getPhone())
                .birthDate(savedCustomer.getBirthDate())
                .createdAt(savedCustomer.getCreatedAt())
                .build();
    }
}
