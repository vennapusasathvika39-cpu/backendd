package com.frameforge.service;

import com.frameforge.dto.Auth;
import com.frameforge.model.User;
import com.frameforge.repository.UserRepository;
import com.frameforge.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder encoder;

    public Auth.Response register(Auth.Request req) {
        if (req.getUsername() == null || req.getUsername().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        if (req.getPassword() == null || req.getPassword().length() < 6)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        if (userRepo.existsByUsername(req.getUsername()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");

        User user = new User(null, req.getUsername().trim(), encoder.encode(req.getPassword()));
        userRepo.save(user);
        return new Auth.Response(jwtUtil.generate(user.getUsername()), user.getUsername());
    }

    public Auth.Response login(Auth.Request req) {
        User user = userRepo.findByUsername(req.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!encoder.matches(req.getPassword(), user.getPassword()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        return new Auth.Response(jwtUtil.generate(user.getUsername()), user.getUsername());
    }
}
