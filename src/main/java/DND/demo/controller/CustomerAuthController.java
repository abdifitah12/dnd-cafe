package DND.demo.controller;

import DND.demo.entity.Customer;
import DND.demo.repository.CustomerRepository;
import DND.demo.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/api/customers/auth")
@CrossOrigin(origins = {"http://localhost:5173", "https://dndcafe.to", "https://www.dndcafe.to",
        "https://d-d-frontend-sigma.vercel.app"})
public class CustomerAuthController {
    private final CustomerRepository customers;
    private final PasswordEncoder passwords;
    private final JwtService tokens;

    public CustomerAuthController(CustomerRepository customers, PasswordEncoder passwords, JwtService tokens) {
        this.customers = customers;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    public record RegisterRequest(@NotBlank @Size(max = 80) String username,
                                  @NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}
    public record LoginRequest(@NotBlank @Size(max = 254) String username,
                               @NotBlank @Size(max = 72) String password) {}
    public record Profile(String username, String email) {}
    public record Session(String token, Profile customer) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Session register(@Valid @RequestBody RegisterRequest request) {
        validatePasswordLength(request.password());
        Customer customer = new Customer();
        customer.setUsername(normalize(request.username()));
        customer.setEmail(normalize(request.email()));
        customer.setPassword(passwords.encode(request.password()));
        try {
            customers.saveAndFlush(customer);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This username or email is already registered.");
        }
        return session(customer);
    }

    @PostMapping("/login")
    public Session login(@Valid @RequestBody LoginRequest request) {
        validatePasswordLength(request.password());
        String identifier = normalize(request.username());
        Customer customer = customers.findByUsernameOrEmail(identifier, identifier)
                .orElseThrow(this::invalidCredentials);
        if (!passwords.matches(request.password(), customer.getPassword())) {
            throw invalidCredentials();
        }
        return session(customer);
    }

    @GetMapping("/me")
    public Profile me(Authentication authentication) {
        Customer customer = customers.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return profile(customer);
    }

    private Session session(Customer customer) {
        return new Session(tokens.generateCustomerToken(customer), profile(customer));
    }

    private Profile profile(Customer customer) {
        return new Profile(customer.getUsername(), customer.getEmail());
    }

    private String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain at most 72 UTF-8 bytes.");
        }
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
    }
}
