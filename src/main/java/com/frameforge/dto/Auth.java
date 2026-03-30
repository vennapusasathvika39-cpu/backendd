package com.frameforge.dto;

import lombok.Data;

public class Auth {

    @Data
    public static class Request {
        private String username;
        private String password;
    }

    @Data
    public static class Response {
        private final String token;
        private final String username;
    }
}
