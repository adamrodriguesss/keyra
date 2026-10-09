package com.rodrigues.keyra.controller;

import static com.rodrigues.keyra.controller.AuthApi.PASSWORD;
import static com.rodrigues.keyra.controller.AuthApi.credentials;
import static com.rodrigues.keyra.controller.AuthApi.refreshBody;
import static com.rodrigues.keyra.controller.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import com.rodrigues.keyra.entity.AccountStatus;
import com.rodrigues.keyra.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class UserAuthIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRepository users;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
    }

    @Test
    void registerStoresEmailLowercasedAndReturnsCreated() {
        String email = uniqueEmail();

        assertThat(api.register(email.toUpperCase(Locale.ROOT), PASSWORD))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo(email))
                .doesNotHavePath("$.passwordHash");
        assertThat(users.findByEmail(email)).isPresent();
    }

    @Test
    void registerRejectsDuplicateEmailIgnoringCase() {
        String email = uniqueEmail();
        api.register(email, PASSWORD);

        assertThat(api.register(email.toUpperCase(Locale.ROOT), PASSWORD))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void registerRejectsShortPasswordWithFieldErrors() {
        assertThat(api.register(uniqueEmail(), "too-short"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors.password", v -> v.assertThat().isNotNull());
    }

    @Test
    void passwordsLongerThanBcryptLimitWorkAndEveryCharacterCounts() {
        String email = uniqueEmail();
        String longPassword = "a".repeat(127) + "b";
        api.register(email, longPassword);

        assertThat(api.post("/api/auth/login", credentials(email, longPassword)))
                .hasStatusOk();
        assertThat(api.post("/api/auth/login", credentials(email, "a".repeat(127) + "c")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginReturnsTokensThatAuthenticateMe() {
        String email = uniqueEmail();
        api.register(email, PASSWORD);

        var result = api.post("/api/auth/login", credentials(email, PASSWORD));
        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.tokenType", v -> v.assertThat().isEqualTo("Bearer"))
                .hasPathSatisfying("$.expiresIn", v -> v.assertThat().isEqualTo(900))
                .hasPathSatisfying("$.refreshExpiresIn", v -> v.assertThat().isEqualTo(604800));

        String accessToken = AuthApi.Tokens.from(result).accessToken();
        assertThat(api.get("/api/auth/me", accessToken))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo(email));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailIsUnauthorized() {
        String email = uniqueEmail();
        api.register(email, PASSWORD);

        assertThat(api.post("/api/auth/login", credentials(email, "wrong-password-123")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post("/api/auth/login", credentials(uniqueEmail(), PASSWORD)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meRequiresAToken() {
        assertThat(mvc.get().uri("/api/auth/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().startsWith("No access token was sent"));
        assertThat(mvc.get().uri("/api/auth/me")).headers().containsHeader("WWW-Authenticate");
    }

    @Test
    void refreshRotatesTheRefreshToken() {
        var tokens = api.registerAndLogin();

        var refreshed = api.post("/api/auth/refresh", refreshBody(tokens.refreshToken()));
        assertThat(refreshed).hasStatusOk();
        var newTokens = AuthApi.Tokens.from(refreshed);
        assertThat(newTokens.refreshToken()).isNotEqualTo(tokens.refreshToken());

        assertThat(api.post("/api/auth/refresh", refreshBody(tokens.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post("/api/auth/refresh", refreshBody(newTokens.refreshToken())))
                .hasStatusOk();
    }

    @Test
    void concurrentRefreshesWithTheSameTokenOnlySucceedOnce() throws Exception {
        var tokens = api.registerAndLogin();
        int attempts = 8;
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                statuses.add(pool.submit(() -> {
                    start.await();
                    return api.post("/api/auth/refresh", refreshBody(tokens.refreshToken()))
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }
            assertThat(results).containsOnlyOnce(200).containsOnly(200, 401);
        }
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        var tokens = api.registerAndLogin();

        assertThat(api.post("/api/auth/logout", refreshBody(tokens.refreshToken())))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(api.post("/api/auth/refresh", refreshBody(tokens.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void disabledUserCannotLoginOrRefresh() {
        String email = uniqueEmail();
        api.register(email, PASSWORD);
        var tokens = api.userLogin(email, PASSWORD);

        var user = users.findByEmail(email).orElseThrow();
        user.setStatus(AccountStatus.DISABLED);
        users.save(user);

        assertThat(api.post("/api/auth/login", credentials(email, PASSWORD))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post("/api/auth/refresh", refreshBody(tokens.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
