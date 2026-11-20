package DND.demo.security;




import DND.demo.entity.Admin;
import DND.demo.entity.Role;
import DND.demo.repository.AdminRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .cors(cors -> {})

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/customers/auth/register",
                                "/api/customers/auth/login").permitAll()
                        .requestMatchers("/api/customers/**").hasRole("CUSTOMER")
                        .requestMatchers("/ws/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/live/participants").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/live/watch").permitAll()

                        .requestMatchers("/api/chat/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/chat/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/chat/conversations",
                                "/api/chat/conversations/*/messages").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/chat/conversations/*/messages").permitAll()

                        // LOGIN MUST BE PUBLIC
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login"
                        ).permitAll()

                        // PUBLIC
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/events/**",
                                "/api/event-history/**"
                        ).permitAll()

                        // ADMIN ONLY
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/events/**",
                                "/api/event-history/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/events/**",
                                "/api/event-history/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/events/**",
                                "/api/event-history/**"
                        ).hasRole("ADMIN")

                        .anyRequest().authenticated()
                );

        http.addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
        );

        return http.build();
    }
    @Bean
    CommandLineRunner createAdmin(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder
    ) {
        return args -> {
            if (adminRepository.findByUsername("qamar").isEmpty()) {
                Admin admin = new Admin();
                admin.setUsername("qamar");
                admin.setPassword(passwordEncoder.encode("diriye10"));
                admin.setRole(Role.ADMIN);

                adminRepository.save(admin);
            }
        };
    }
}
