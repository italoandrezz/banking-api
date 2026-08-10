package com.italo.bankingapi.repository;

import com.italo.bankingapi.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByEmail(String email);
    boolean existsByCpf(String cpf);

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByCpf(String cpf);

}