package com.italo.bankingapi.controller;

import com.italo.bankingapi.dto.customer.CreateCustomerRequest;
import com.italo.bankingapi.dto.customer.CustomerResponse;
import com.italo.bankingapi.dto.customer.UpdateCustomerRequest;
import com.italo.bankingapi.dto.error.ErrorResponse;
import com.italo.bankingapi.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
@Tag(
        name = "Customers",
        description = "Endpoints for customer management"
)
public class CustomerController {

    private final CustomerService customerService;

    @Operation(
            summary = "Create a new customer",
            description = "Creates a new customer in the banking system."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Customer created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Customer already exists",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Customer data required to create a new customer."
    )
            @Valid
            @RequestBody CreateCustomerRequest request) {
        CustomerResponse response = customerService.createCustomer(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
    @Operation(
            summary = "List all customers",
            description = "Returns all customers registered in the banking system."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Customers retrieved successfully"
            )
    })
    @GetMapping
    public ResponseEntity<List<CustomerResponse>> findAllCustomers() {
        return ResponseEntity.ok(customerService.findAllCustomers());
    }
    @Operation(
            summary = "Find customer by ID",
            description = "Returns a customer using the provided UUID."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Customer found successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Customer not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> findCustomerById(
            @Parameter(description = "Customer UUID")
            @PathVariable UUID id) {
        CustomerResponse response = customerService.findCustomerById(id);
        return ResponseEntity.ok(response);
    }
    @Operation(
            summary = "Update customer",
            description = "Updates the data of an existing customer."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Customer updated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Customer not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Customer already exists",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> updateCustomer(
            @Parameter(description = "Customer UUID")
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        CustomerResponse response = customerService.updateCustomer(id, request);
        return ResponseEntity.ok(response);
    }
    @Operation(
            summary = "Delete customer",
            description = "Deletes an existing customer."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Customer deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Customer not found",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(
            @Parameter(description = "Customer UUID")
            @PathVariable UUID id) {
        customerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }
}
