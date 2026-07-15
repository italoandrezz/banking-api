package com.italo.bankingapi.repository;

import com.italo.bankingapi.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    // Verifica se já existe um cliente com este e-mail
    boolean existsByEmail(String email);

    // Verifica se já existe um cliente com este CPF
    boolean existsByCpf(String cpf);

}