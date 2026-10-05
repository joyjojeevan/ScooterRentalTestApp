package lk.scooterrentkandy.controllers;

import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.dto.UserDtos.UserResponse;
import lk.scooterrentkandy.services.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final CustomerService customers;

    public AdminCustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    public List<UserResponse> list() {
        return customers.list();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return customers.get(id);
    }

    @PatchMapping("/{id}/active")
    public UserResponse setActive(@PathVariable UUID id, @RequestParam boolean value) {
        return customers.setActive(id, value);
    }
}
