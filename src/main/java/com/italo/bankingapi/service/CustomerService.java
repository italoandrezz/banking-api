package com.italo.bankingapi.service;

import com.italo.bankingapi.config.security.AuthenticatedCustomer;
import com.italo.bankingapi.dto.customer.CreateCustomerRequest;
import com.italo.bankingapi.dto.customer.CustomerResponse;
import com.italo.bankingapi.dto.customer.UpdateCustomerRequest;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticatedCustomer authenticatedCustomer;

    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        if (customerRepository.existsByCpf(request.getCpf())) {
            throw new ConflictException("CPF already registered.");
        }
        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("E-mail already registered.");
        }
        Customer customer = Customer.builder()
                .role(com.italo.bankingapi.enums.CustomerRole.CUSTOMER)
                .fullName(request.getFullName())
                .cpf(request.getCpf())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .phone(request.getPhone())
                .birthDate(request.getBirthDate())
                .createdAt(LocalDateTime.now())
                .build();
        Customer savedCustomer = customerRepository.save(customer);
        return toCustomerResponse(savedCustomer);
    }
    public CustomerResponse findCustomerById(UUID id) {
        Customer customer = findCustomerOrThrow(id);
        return toCustomerResponse(customer);
    }
    public List<CustomerResponse> findAllCustomers() {
        authenticatedCustomer.getCustomer();
        throw new AccessDeniedException("Customer listing is not allowed.");
    }
    public CustomerResponse updateCustomer(UUID id, UpdateCustomerRequest request) {
        Customer customer = findCustomerOrThrow(id);
        boolean emailChanged = !customer.getEmail().equals(request.getEmail());
        if (emailChanged && customerRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("E-mail already registered.");
        }
        customer.setFullName(request.getFullName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setBirthDate(request.getBirthDate());
        Customer updatedCustomer = customerRepository.save(customer);
        return toCustomerResponse(updatedCustomer);
    }
    public void deleteCustomer(UUID id) {
        Customer customer = findCustomerOrThrow(id);
        try {
            customerRepository.delete(customer);
        } catch (DataIntegrityViolationException exception) {
            // The database also protects concurrent creation of dependent records.
            throw new ConflictException("Customer has related records and cannot be deleted.");
        }
    }
    private Customer findCustomerOrThrow(UUID id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
        authenticatedCustomer.requireOwner(customer.getId());
        return customer;
    }
    private CustomerResponse toCustomerResponse(Customer customer) {
        return CustomerResponse.builder()
                .id(customer.getId())
                .fullName(customer.getFullName())
                .cpf(customer.getCpf())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .birthDate(customer.getBirthDate())
                .createdAt(customer.getCreatedAt())
                .build();
    }
}
