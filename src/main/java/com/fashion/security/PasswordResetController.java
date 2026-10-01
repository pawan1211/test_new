package com.fashion.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/customer")
@CrossOrigin(origins = "${app.cors-origin:http://localhost:3000}")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        if (request == null || request.email == null || request.email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email address is required");
        }
        passwordResetService.requestPasswordReset(request.email);
        return Map.of(
            "message", "If an account exists for this email, a password reset link has been sent.",
            "sent", true
        );
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody ResetPasswordRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        passwordResetService.resetPassword(request.token, request.newPassword, request.confirmPassword);
        return Map.of(
            "message", "Your password has been reset successfully. You can now sign in with your new password.",
            "reset", true
        );
    }

    public static class ForgotPasswordRequest {public String email;}
    public static class ResetPasswordRequest {public String token;public String newPassword;public String confirmPassword;}
}
