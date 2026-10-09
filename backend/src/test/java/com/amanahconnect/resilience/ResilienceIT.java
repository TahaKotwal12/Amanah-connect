package com.amanahconnect.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractDeskIT;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class ResilienceIT extends AbstractDeskIT {

    @Autowired Environment env;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.mail.javamail.JavaMailSender mailSender;

    @Test
    void shutdownIsGracefulAndGivesInFlightRequestsTimeToFinish() {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
    }

    @Test
    void smtpCallsCannotHangAWorker() {
        var session = ((JavaMailSenderImpl) mailSender).getSession();
        assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
        assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("10000");
        assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
    }

    @Test
    void thePoolIsConfiguredForNeonColdStartsAndDroppedIdleConnections() {
        HikariDataSource pool = (HikariDataSource) dataSource;
        assertThat(pool.getInitializationFailTimeout()).as("startup retries for a minute").isEqualTo(60_000);
        assertThat(pool.getKeepaliveTime()).isEqualTo(120_000);
        assertThat(pool.getMaxLifetime()).isLessThan(30 * 60_000L);
        assertThat(pool.getValidationTimeout()).isEqualTo(3000);
        assertThat(pool.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(pool.getLeakDetectionThreshold()).as("tests run with leak detection on").isEqualTo(20_000);
        assertThat(env.getProperty("spring.flyway.connect-retries", Integer.class)).isEqualTo(10);
    }

    @Test
    void noRequestLeavesAConnectionBehind() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        member(sessionA, "Leak Check", email(), true);
        for (int i = 0; i < 40; i++) {
            asA("GET", "/api/v1/community/members", null);
            asA("GET", "/api/v1/community/dashboard", null);
            asA("GET", "/api/v1/community/audit", null);
            asA("POST", "/api/v1/community/members", Map.of()); // validation failure path
            asA("GET", "/api/v1/community/members/" + java.util.UUID.randomUUID(), null); // 404 path
        }
        asSuper("GET", "/api/v1/admin/communities", null);

        long deadline = System.currentTimeMillis() + 3000;
        while (pool.getActiveConnections() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);

        assertThat(pool.getActiveConnections()).as("every connection was handed back").isZero();
        assertThat(pool.getThreadsAwaitingConnection()).isZero();
    }

    /** A proxy in front of the real database that hangs up on the first connections, like a Neon compute that is still waking up. */
    @Test
    void theApplicationsPoolSettingsRideOutADatabaseThatDropsTheFirstConnections() throws Exception {
        HikariDataSource real = (HikariDataSource) dataSource;
        URI target = URI.create(real.getJdbcUrl().substring("jdbc:".length()));
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService threads = Executors.newCachedThreadPool();
        try (ServerSocket proxy = new ServerSocket(0)) {
            threads.submit(() -> {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
                    if (accepted.incrementAndGet() <= 3) {
                        client.close(); // the "cold" database refuses the first three
                        continue;
                    }
                    Socket upstream = new Socket();
                    upstream.connect(new InetSocketAddress(target.getHost(), target.getPort()));
                    threads.submit(() -> pump(client, upstream));
                    threads.submit(() -> pump(upstream, client));
                }
                return null;
            });
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://localhost:" + proxy.getLocalPort() + target.getPath());
            config.setUsername(real.getUsername());
            config.setPassword(real.getPassword());
            config.setInitializationFailTimeout(real.getInitializationFailTimeout());
            config.setConnectionTimeout(real.getConnectionTimeout());
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(1);

            try (HikariDataSource pool = new HikariDataSource(config); var c = pool.getConnection(); var rs = c.createStatement().executeQuery("select 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(accepted.get()).as("it kept trying past the refused connections").isGreaterThan(3);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    private static void pump(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // the other side hung up
        } finally {
            try { from.close(); } catch (IOException ignored) { }
            try { to.close(); } catch (IOException ignored) { }
        }
    }
}
