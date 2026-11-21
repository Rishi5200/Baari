package com.baari.simulator;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a {@link DrivePolicy} against a live Baari server through the same staff API the reception
 * console uses: logs in, reads the board, opens the day, adds walk-ins, calls, starts and completes.
 * Nothing in the app knows it is being driven.
 */
final class LiveDriver {

    /** The session expired or the CSRF token went stale: log in again. */
    static final class SessionLost extends RuntimeException {
        SessionLost(String message) {
            super(message);
        }
    }

    private static final Pattern FORM_CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final Pattern META_HEADER = Pattern.compile("name=\"csrf-header\"\\s+content=\"([^\"]+)\"");
    private static final Pattern META_TOKEN = Pattern.compile("name=\"csrf-token\"\\s+content=\"([^\"]+)\"");

    private final URI base;
    private final String clinic;
    private final String queue;
    private final String username;
    private final String password;
    private final HttpClient http;
    private String queueId;
    private String csrfHeader;
    private String csrfToken;

    LiveDriver(URI base, String clinic, String queue, String username, String password) {
        this.base = base;
        this.clinic = clinic;
        this.queue = queue;
        this.username = username;
        this.password = password;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Ticks forever; a failed tick is logged and retried on the next one. */
    void run(DrivePolicy policy, Duration tick, Clock clock) throws InterruptedException {
        boolean loggedIn = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!loggedIn) {
                    login();
                    loggedIn = true;
                    System.out.println("drive: logged in, driving " + clinic + "/" + queue);
                }
                for (DrivePolicy.Action action : policy.decide(board(), clock.instant(), tick)) {
                    apply(action, policy, clock);
                }
            } catch (SessionLost e) {
                System.out.println("drive: " + e.getMessage() + ", logging in again");
                loggedIn = false;
            } catch (IOException | RuntimeException e) {
                System.out.println("drive: tick failed: " + e);
            }
            Thread.sleep(tick.toMillis());
        }
    }

    void login() throws IOException, InterruptedException {
        if (queueId == null) {
            HttpResponse<String> info = send(get("/api/public/queues/" + clinic + "/" + queue));
            expect(info, 200, "queue info");
            queueId = (String) json(info).get("queueId");
        }
        String loginPage = send(get("/login")).body();
        String form = "username=" + enc(username) + "&password=" + enc(password) + "&_csrf="
                + enc(find(FORM_CSRF, loginPage, "login form CSRF field"));
        HttpResponse<String> res = send(HttpRequest.newBuilder(base.resolve("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
        String location = res.headers().firstValue("Location").orElse("");
        if (res.statusCode() != 302 || location.contains("error")) {
            throw new IllegalStateException("login failed (HTTP " + res.statusCode() + "): check user and password");
        }
        String console = send(get("/staff/queues/" + queueId)).body();
        csrfHeader = find(META_HEADER, console, "csrf-header meta");
        csrfToken = find(META_TOKEN, console, "csrf-token meta");
    }

    DrivePolicy.Board board() throws IOException, InterruptedException {
        HttpResponse<String> res = send(get("/api/staff/queues/" + queueId + "/board"));
        checkSession(res);
        if (res.statusCode() == 404 || res.statusCode() == 409) {
            return DrivePolicy.Board.noDay(); // no day today yet
        }
        expect(res, 200, "board");
        return parseBoard(res.body());
    }

    @SuppressWarnings("unchecked")
    static DrivePolicy.Board parseBoard(String body) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(body);
        Map<String, Object> day = (Map<String, Object>) root.get("day");
        return new DrivePolicy.Board(
                (String) day.get("status"),
                toks(root.get("waiting")),
                toks(root.get("called")),
                toks(root.get("inConsultation")));
    }

    @SuppressWarnings("unchecked")
    private static List<DrivePolicy.Tok> toks(Object list) {
        List<DrivePolicy.Tok> out = new ArrayList<>();
        for (Object o : (List<Object>) list) {
            Map<String, Object> t = (Map<String, Object>) o;
            out.add(new DrivePolicy.Tok(
                    (String) t.get("id"),
                    (String) t.get("visitType"),
                    instant(t.get("calledAt")),
                    instant(t.get("consultStartedAt"))));
        }
        return out;
    }

    private void apply(DrivePolicy.Action action, DrivePolicy policy, Clock clock)
            throws IOException, InterruptedException {
        String q = "/api/staff/queues/" + queueId;
        HttpResponse<String> res = switch (action) {
            case DrivePolicy.OpenDay o -> post(q + "/days/today/open", null);
            case DrivePolicy.WalkIn w ->
                post(
                        q + "/tokens",
                        "{\"name\":\"" + w.name() + "\",\"visitType\":\"" + w.visitType()
                                + "\",\"priority\":\"" + w.priority() + "\",\"idempotencyKey\":\""
                                + UUID.randomUUID() + "\"}");
            case DrivePolicy.CallNext c -> post(q + "/call-next", null);
            case DrivePolicy.Start s -> post("/api/staff/tokens/" + s.tokenId() + "/start", null);
            case DrivePolicy.Complete c -> post("/api/staff/tokens/" + c.tokenId() + "/complete", null);
        };
        checkSession(res);
        String code = res.statusCode() < 300 ? (String) json(res).getOrDefault("displayCode", "") : "";
        System.out.println("drive: " + describe(action) + " " + code + " -> HTTP " + res.statusCode());
        if (action instanceof DrivePolicy.Complete && res.statusCode() == 200) {
            policy.completed(clock.instant());
        }
    }

    private static String describe(DrivePolicy.Action action) {
        return switch (action) {
            case DrivePolicy.OpenDay o -> "open day";
            case DrivePolicy.WalkIn w -> "walk-in " + w.visitType() + "/" + w.priority();
            case DrivePolicy.CallNext c -> "call next";
            case DrivePolicy.Start s -> "start";
            case DrivePolicy.Complete c -> "complete";
        };
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header(csrfHeader, csrfToken);
        if (json == null) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        }
        return send(b.build());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** An expired session shows up as 401, a redirect to the login page, or 403 (stale CSRF token). */
    private static void checkSession(HttpResponse<String> res) {
        int s = res.statusCode();
        if (s == 401 || s == 403 || s == 302) {
            throw new SessionLost("HTTP " + s + " from " + res.uri().getPath());
        }
    }

    private static void expect(HttpResponse<String> res, int status, String what) {
        if (res.statusCode() != status) {
            throw new IllegalStateException(what + ": HTTP " + res.statusCode());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HttpResponse<String> res) {
        return (Map<String, Object>) Json.parse(res.body());
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse((String) value);
    }

    private static String find(Pattern p, String html, String what) {
        Matcher m = p.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("page has no " + what);
        }
        return m.group(1);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
