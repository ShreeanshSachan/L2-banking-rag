package com.example.demo.service;

import com.example.demo.dto.AuthDtos.AuthResponse;
import com.example.demo.dto.AuthDtos.LoginRequest;
import com.example.demo.dto.AuthDtos.RegisterRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final AccountProvisioningService accountProvisioningService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authenticationManager,
                       AuditService auditService,
                       AccountProvisioningService accountProvisioningService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.auditService = auditService;
        this.accountProvisioningService = accountProvisioningService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username already exists");
        }
        User user = new User(request.username(), passwordEncoder.encode(request.password()), "USER");
        user = userRepository.save(user);
        accountProvisioningService.provisionForNewUser(user.getId());
        String token = jwtService.generateToken(user.getId(), user.getUsername(), List.of("USER"));
        auditService.authEvent("REGISTER", user.getUsername(), user.getId());
        return new AuthResponse(token, "Bearer", user.getUsername(), user.getId(), user.getRoles());
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            auditService.authEvent("LOGIN_FAILED", request.username(), null);
            throw new BadCredentialsException("Invalid username or password");
        }
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        List<String> roles = List.of(user.getRoles().split(","));
        String token = jwtService.generateToken(user.getId(), user.getUsername(), roles);
        auditService.authEvent("LOGIN_SUCCESS", user.getUsername(), user.getId());
        return new AuthResponse(token, "Bearer", user.getUsername(), user.getId(), user.getRoles());
    }
}
