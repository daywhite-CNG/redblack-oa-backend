package com.redblack.approval.infrastructure.identity;

import com.redblack.approval.application.BusinessException;
import com.redblack.approval.infrastructure.security.ApprovalSecurityProperties;
import com.redblack.approval.infrastructure.security.ApprovalServiceTokenIssuer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class IdentityClientTimeoutTest {
    @Test
    void connectedIdentityServiceThatNeverRespondsFailsWithinConfiguredTimeout() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon(true).start(() -> acceptWithoutResponding(server, accepted, release));

            ApprovalSecurityProperties properties = new ApprovalSecurityProperties();
            properties.setIdentityInternalUrl("http://127.0.0.1:" + server.getLocalPort());
            properties.setIdentityConnectTimeout(Duration.ofMillis(100));
            properties.setIdentityResponseTimeout(Duration.ofMillis(250));
            properties.setInternalSecret("0123456789abcdef0123456789abcdef");
            ApprovalServiceTokenIssuer issuer = new ApprovalServiceTokenIssuer(properties, Clock.systemUTC());
            IdentityClient client = new IdentityClient(properties, issuer);

            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                        assertThatThrownBy(() -> client.authorization(10001L, "req-stalled-identity"))
                                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                                    assertThat(exception.status().value()).isEqualTo(503);
                                    assertThat(exception.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                                }));
                assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    private void acceptWithoutResponding(ServerSocket server,
                                         CountDownLatch accepted,
                                         CountDownLatch release) {
        try (Socket ignored = server.accept()) {
            accepted.countDown();
            release.await(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The test owns and closes the socket.
        }
    }
}
