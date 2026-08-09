package com.redblack.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BaselinePasswordTest {
    @Test
    void seededBcryptHashMatchesDocumentedInitialPassword() {
        String hash = "$2a$12$itwchbM87ay7kHYj2uORS./5fYTS/oxJMw93CDlu0dnVQSQVMdXp2";
        assertThat(new BCryptPasswordEncoder(12).matches("123456", hash)).isTrue();
        assertThat(new BCryptPasswordEncoder(12).matches("wrong-password", hash)).isFalse();
    }
}
