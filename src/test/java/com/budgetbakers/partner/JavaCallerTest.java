// A Java caller uses the client with plain Java: constructors, getters,
// static helpers and Iterable walks, no Kotlin-only syntax.

package com.budgetbakers.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaCallerTest {

    private HttpServer server;
    private final Deque<String[]> replies = new ArrayDeque<>();
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            String[] reply = replies.poll();
            byte[] body = reply[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(reply[0]), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsAClientWithTheMinimalConstructor() {
        replies.add(new String[] {"201", "{\"data\":{\"id\":\"c1\",\"externalId\":\"u1\",\"email\":\"u@x.test\",\"countryCode\":\"CZ\"}}"});
        try (BudgetBakers bb = new BudgetBakers("bb_test_java", baseUrl)) {
            Client client = bb.clients().create(new ClientCreateRequest("u@x.test", "CZ", "u1"));
            assertEquals("c1", client.getId());
            assertEquals("u1", client.getExternalId());
        }
        assertEquals(List.of("POST /v2/clients"), paths);
    }

    @Test
    void walksProvidersAndScopesToAClient() {
        replies.add(new String[] {"200", "{\"limit\":1,\"nextCursor\":\"c2\",\"data\":[{\"id\":\"p1\",\"name\":\"One\"}]}"});
        replies.add(new String[] {"200", "{\"limit\":1,\"nextCursor\":null,\"data\":[{\"id\":\"p2\",\"name\":\"Two\"}]}"});
        replies.add(new String[] {"200", "{\"data\":{\"id\":\"x1\",\"state\":\"Active\",\"providerId\":\"p1\",\"consentExpiresAt\":null}}"});
        BudgetBakers bb = BudgetBakers.builder("bb_test_java").baseUrl(baseUrl).maxRetries(0).build();
        List<String> ids = new ArrayList<>();
        for (Provider provider : bb.providers().list()) {
            ids.add(provider.getId());
        }
        assertEquals(List.of("p1", "p2"), ids);
        Connection connection = bb.client("c1").connections().get("x1");
        assertEquals("Active", connection.getState());
    }

    @Test
    void errorsAreTypedByCode() {
        replies.add(new String[] {"404", "{\"error\":{\"code\":\"not_found\",\"message\":\"no such client\"},\"requestId\":\"req_1\"}"});
        BudgetBakers bb = new BudgetBakers("bb_test_java", baseUrl);
        PartnerApiException e = assertThrows(PartnerApiException.class, () -> bb.clients().get("missing"));
        assertEquals(ErrorCode.NOT_FOUND, e.getCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("req_1", e.getRequestId());
    }

    @Test
    void verifiesAndParsesAWebhookStatically() {
        String body = "{\"eventId\":\"e1\",\"type\":\"ConnectionCreateSuccess\",\"clientId\":\"c1\",\"connectionId\":\"x1\",\"createdAt\":\"2026-07-15T08:10:30.000Z\"}";
        String header = Webhooks.sign("whsec_test_java", 1784102400L, body);
        assertEquals(VerifyResult.VALID, Webhooks.verify(List.of("whsec_test_java"), header, body, 1784102400L));
        assertEquals(VerifyResult.INVALID_SIGNATURE, Webhooks.verify(List.of("whsec_other"), header, body, 1784102400L));
        assertEquals("X-BB-Signature", Webhooks.SIGNATURE_HEADER);
        ParsedWebhook parsed = Webhooks.parseEvent(body);
        WebhookEvent event = assertInstanceOf(WebhookEvent.class, parsed);
        assertEquals(WebhookEventType.ConnectionCreateSuccess, event.getType());
        assertEquals(12345L, Money.toCents("123.45"));
    }
}
