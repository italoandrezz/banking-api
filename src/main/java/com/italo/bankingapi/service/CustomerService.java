package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.customer.CreateCustomerRequest;
import com.italo.bankingapi.dto.customer.CustomerResponse;
import com.italo.bankingapi.dto.customer.UpdateCustomerRequest;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

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
        List<Customer> customers = customerRepository.findAll();
        return customers.stream().map(this::toCustomerResponse).toList();
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
        customerRepository.delete(customer);
    }
    private Customer findCustomerOrThrow(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
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
