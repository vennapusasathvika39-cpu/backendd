package com.frameforge.controller;

import com.frameforge.dto.Auth;
import com.frameforge.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Auth.Response register(@RequestBody Auth.Request req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public Auth.Response login(@RequestBody Auth.Request req) {
        return authService.login(req);
    }
}
