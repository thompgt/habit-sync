package dev.thompgt.habitsync.auth;

import dev.thompgt.habitsync.account.AccountRepository;
import dev.thompgt.habitsync.account.AppUser;
import dev.thompgt.habitsync.auth.AuthDtos.DeviceResponse;
import dev.thompgt.habitsync.auth.AuthDtos.LoginRequest;
import dev.thompgt.habitsync.auth.AuthDtos.MeResponse;
import dev.thompgt.habitsync.auth.AuthDtos.RefreshRequest;
import dev.thompgt.habitsync.auth.AuthDtos.RegisterRequest;
import dev.thompgt.habitsync.auth.AuthDtos.TokenResponse;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AuthController {

    private final AuthService authService;
    private final AccountRepository accounts;

    public AuthController(AuthService authService, AccountRepository accounts) {
        this.authService = authService;
        this.accounts = accounts;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(request.email(), request.password(), request.deviceName());
        return ResponseEntity.status(HttpStatus.CREATED).body(TokenResponse.from(result));
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.from(
                authService.login(
                        request.email(), request.password(), request.deviceName(), request.deviceId()));
    }

    @PostMapping("/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(authService.refresh(request.refreshToken()));
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        AppUser user = accounts
                .findById(principal.userId())
                .orElseThrow(() -> new AuthenticationFailedException("User no longer exists"));

        return new MeResponse(
                user.id(),
                user.email(),
                accounts.findDevices(user.id()).stream()
                        .map(d -> new DeviceResponse(
                                d.id(),
                                d.displayName(),
                                d.lastSeenSeq(),
                                d.lastSeenAt() == null ? null : d.lastSeenAt().toString()))
                        .toList());
    }
}
