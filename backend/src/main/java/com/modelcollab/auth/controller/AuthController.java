package com.modelcollab.auth.controller;

import com.modelcollab.auth.dto.AuthResponse;
import com.modelcollab.auth.dto.LoginRequest;
import com.modelcollab.auth.dto.RegisterRequest;
import com.modelcollab.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos de autenticación (§14). Contrato congelado (D-11). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 201 con el token, o 409 si el email ya existe. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest peticion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(peticion));
    }

    /** 200 con el token, o 401 si las credenciales no son válidas. */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest peticion) {
        return authService.login(peticion);
    }
}
