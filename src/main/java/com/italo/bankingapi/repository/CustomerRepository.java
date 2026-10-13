package com.italo.bankingapi.repository;

import com.italo.bankingapi.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Customer c set c.fullName = :name, c.email = :email, c.phone = :phone, "
            + "c.birthDate = :birthDate, c.updatedAt = :updatedAt where c.id = :id")
    int updateProfile(@Param("id") UUID id, @Param("name") String name,
                      @Param("email") String email, @Param("phone") String phone,
                      @Param("birthDate") LocalDate birthDate, @Param("updatedAt") LocalDateTime updatedAt);

    boolean existsByEmail(String email);
    boolean existsByCpf(String cpf);

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByCpf(String cpf);

}
