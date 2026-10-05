package lk.scooterrentkandy.services;

import java.util.List;
import java.util.UUID;
import lk.scooterrentkandy.dto.UserDtos.UserResponse;
import lk.scooterrentkandy.exception.ApiException;
import lk.scooterrentkandy.mappers.UserMapper;
import lk.scooterrentkandy.models.Role;
import lk.scooterrentkandy.models.User;
import lk.scooterrentkandy.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of customer (USER) accounts. */
@Service
public class CustomerService {

    private final UserRepository users;

    public CustomerService(UserRepository users) {
        this.users = users;
    }

    public List<UserResponse> list() {
        return users.findByRoleOrderByCreatedAtDesc(Role.USER).stream().map(UserMapper::toResponse).toList();
    }

    public UserResponse get(UUID id) {
        return UserMapper.toResponse(find(id));
    }

    @Transactional
    public UserResponse setActive(UUID id, boolean value) {
        User user = find(id);
        if (user.getRole() != Role.USER) {
            throw ApiException.badRequest("Only customer (USER) accounts can be toggled here");
        }
        user.setActive(value);
        return UserMapper.toResponse(users.save(user));
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
    }
}
