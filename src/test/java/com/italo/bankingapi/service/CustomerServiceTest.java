package com.italo.bankingapi.service;

import com.italo.bankingapi.dto.customer.CreateCustomerRequest;
import com.italo.bankingapi.dto.customer.CustomerResponse;
import com.italo.bankingapi.dto.customer.UpdateCustomerRequest;
import com.italo.bankingapi.entity.Customer;
import com.italo.bankingapi.exception.ConflictException;
import com.italo.bankingapi.exception.NotFoundException;
import com.italo.bankingapi.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private CustomerService customerService;

    @Test
    void shouldCreateCustomerSuccessfully() {

        // Arrange
        CreateCustomerRequest request = CreateCustomerRequest.builder()
                .fullName("Italo Teste")
                .cpf("12345678901")
                .email("italo@test.com")
                .password("12345678")
                .phone("81999999999")
                .birthDate(LocalDate.of(2000, 1, 1))
                .build();

        when(customerRepository.existsByEmail(request.getEmail()))
                .thenReturn(false);

        when(customerRepository.existsByCpf(request.getCpf()))
                .thenReturn(false);

        when(passwordEncoder.encode(request.getPassword()))
                .thenReturn("hashed-password");

        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> {
                    Customer customer = invocation.getArgument(0);
                    customer.setId(UUID.randomUUID());
                    return customer;
                });

        // Act
        CustomerResponse response = customerService.createCustomer(request);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getId());
        assertEquals(request.getFullName(), response.getFullName());
        assertEquals(request.getCpf(), response.getCpf());
        assertEquals(request.getEmail(), response.getEmail());
        assertEquals(request.getPhone(), response.getPhone());
        assertEquals(request.getBirthDate(), response.getBirthDate());

        verify(customerRepository, times(1))
                .existsByEmail(request.getEmail());

        verify(customerRepository, times(1))
                .existsByCpf(request.getCpf());

        verify(passwordEncoder, times(1))
                .encode(request.getPassword());

        ArgumentCaptor<Customer> customerCaptor =
                ArgumentCaptor.forClass(Customer.class);

        verify(customerRepository, times(1))
                .save(customerCaptor.capture());

        Customer savedCustomer = customerCaptor.getValue();

        assertEquals("hashed-password", savedCustomer.getPassword());
        assertNotNull(savedCustomer.getCreatedAt());
    }

    @Test
    void shouldThrowConflictExceptionWhenCpfAlreadyExists() {

        // Arrange
        CreateCustomerRequest request = CreateCustomerRequest.builder()
                .fullName("Italo Teste")
                .cpf("12345678901")
                .email("italo@test.com")
                .password("12345678")
                .phone("81999999999")
                .birthDate(LocalDate.of(2000, 1, 1))
                .build();

        when(customerRepository.existsByCpf(request.getCpf()))
                .thenReturn(true);

        // Act
        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> customerService.createCustomer(request)
        );

        // Assert
        assertEquals("CPF already registered.", exception.getMessage());

        verify(customerRepository, times(1))
                .existsByCpf(request.getCpf());

        verify(customerRepository, never())
                .existsByEmail(any());

        verify(passwordEncoder, never())
                .encode(any());

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenEmailAlreadyExists() {

        // Arrange
        CreateCustomerRequest request = CreateCustomerRequest.builder()
                .fullName("Italo Teste")
                .cpf("12345678901")
                .email("italo@test.com")
                .password("12345678")
                .phone("81999999999")
                .birthDate(LocalDate.of(2000, 1, 1))
                .build();

        when(customerRepository.existsByCpf(request.getCpf()))
                .thenReturn(false);

        when(customerRepository.existsByEmail(request.getEmail()))
                .thenReturn(true);

        // Act
        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> customerService.createCustomer(request)
        );

        // Assert
        assertEquals("E-mail already registered.", exception.getMessage());

        verify(customerRepository, times(1))
                .existsByCpf(request.getCpf());

        verify(customerRepository, times(1))
                .existsByEmail(request.getEmail());

        verify(passwordEncoder, never())
                .encode(any());

        verify(customerRepository, never())
                .save(any(Customer.class));
    }

    @Test
    void shouldFindCustomerByIdSuccessfully() {
        // Arrange
        Customer customer = createCustomer();
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));

        // Act
        CustomerResponse response = customerService.findCustomerById(customer.getId());

        // Assert
        assertCustomerResponse(response, customer);
        verify(customerRepository, times(1)).findById(customer.getId());
    }

    @Test
    void shouldThrowNotFoundExceptionWhenCustomerDoesNotExist() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> customerService.findCustomerById(customerId)
        );

        // Assert
        assertEquals("Customer not found.", exception.getMessage());
        verify(customerRepository, times(1)).findById(customerId);
    }

    @Test
    void shouldListAllCustomersSuccessfully() {
        // Arrange
        Customer firstCustomer = createCustomer();
        Customer secondCustomer = createCustomer();
        secondCustomer.setId(UUID.randomUUID());
        secondCustomer.setEmail("second@test.com");
        when(customerRepository.findAll()).thenReturn(List.of(firstCustomer, secondCustomer));

        // Act
        List<CustomerResponse> responses = customerService.findAllCustomers();

        // Assert
        assertEquals(2, responses.size());
        assertCustomerResponse(responses.get(0), firstCustomer);
        assertCustomerResponse(responses.get(1), secondCustomer);
        verify(customerRepository, times(1)).findAll();
    }

    @Test
    void shouldReturnEmptyListWhenNoCustomersExist() {
        // Arrange
        when(customerRepository.findAll()).thenReturn(List.of());

        // Act
        List<CustomerResponse> responses = customerService.findAllCustomers();

        // Assert
        assertEquals(0, responses.size());
        verify(customerRepository, times(1)).findAll();
    }

    @Test
    void shouldUpdateCustomerWithoutEmailChangeSuccessfully() {
        // Arrange
        Customer customer = createCustomer();
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                "Updated Name", customer.getEmail(), "81988888888", LocalDate.of(1999, 2, 2)
        );
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CustomerResponse response = customerService.updateCustomer(customer.getId(), request);

        // Assert
        assertEquals(request.getFullName(), response.getFullName());
        assertEquals(request.getEmail(), response.getEmail());
        assertEquals(request.getPhone(), response.getPhone());
        assertEquals(request.getBirthDate(), response.getBirthDate());
        verify(customerRepository, never()).existsByEmail(any());
        verify(customerRepository, times(1)).save(customer);
    }

    @Test
    void shouldUpdateCustomerWithAvailableNewEmailSuccessfully() {
        // Arrange
        Customer customer = createCustomer();
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                "Updated Name", "new@test.com", "81988888888", LocalDate.of(1999, 2, 2)
        );
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(customerRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        CustomerResponse response = customerService.updateCustomer(customer.getId(), request);

        // Assert
        assertEquals(request.getEmail(), response.getEmail());
        verify(customerRepository, times(1)).existsByEmail(request.getEmail());
        verify(customerRepository, times(1)).save(customer);
    }

    @Test
    void shouldThrowConflictExceptionWhenUpdatingToExistingEmail() {
        // Arrange
        Customer customer = createCustomer();
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                "Updated Name", "used@test.com", "81988888888", LocalDate.of(1999, 2, 2)
        );
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(customerRepository.existsByEmail(request.getEmail())).thenReturn(true);

        // Act
        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> customerService.updateCustomer(customer.getId(), request)
        );

        // Assert
        assertEquals("E-mail already registered.", exception.getMessage());
        verify(customerRepository, times(1)).existsByEmail(request.getEmail());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void shouldThrowNotFoundExceptionWhenUpdatingNonexistentCustomer() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                "Updated Name", "new@test.com", "81988888888", LocalDate.of(1999, 2, 2)
        );
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> customerService.updateCustomer(customerId, request)
        );

        // Assert
        assertEquals("Customer not found.", exception.getMessage());
        verify(customerRepository, never()).existsByEmail(any());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void shouldDeleteCustomerSuccessfully() {
        // Arrange
        Customer customer = createCustomer();
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));

        // Act
        customerService.deleteCustomer(customer.getId());

        // Assert
        verify(customerRepository, times(1)).findById(customer.getId());
        verify(customerRepository, times(1)).delete(customer);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDeletingNonexistentCustomer() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        // Act
        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> customerService.deleteCustomer(customerId)
        );

        // Assert
        assertEquals("Customer not found.", exception.getMessage());
        verify(customerRepository, never()).delete(any(Customer.class));
    }

    private Customer createCustomer() {
        return Customer.builder()
                .id(UUID.randomUUID())
                .fullName("Italo Teste")
                .cpf("12345678901")
                .email("italo@test.com")
                .password("hashed-password")
                .phone("81999999999")
                .birthDate(LocalDate.of(2000, 1, 1))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void assertCustomerResponse(CustomerResponse response, Customer customer) {
        assertEquals(customer.getId(), response.getId());
        assertEquals(customer.getFullName(), response.getFullName());
        assertEquals(customer.getCpf(), response.getCpf());
        assertEquals(customer.getEmail(), response.getEmail());
        assertEquals(customer.getPhone(), response.getPhone());
        assertEquals(customer.getBirthDate(), response.getBirthDate());
        assertEquals(customer.getCreatedAt(), response.getCreatedAt());
    }
}
