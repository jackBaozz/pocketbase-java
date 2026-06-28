package io.github.jackbaozz.pocketbase.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playwright-based smoke tests for Admin UI rendering and navigation.
 * Full CRUD workflows are covered by JsSdkSmokeTest and BehaviorFixturesTest.
 */
public class AdminUiPlaywrightTest {

    private static Playwright playwright;
    private static Browser browser;

    private LocalPocketBase server;
    private String baseUrl;
    private BrowserContext context;
    private Page page;

    @TempDir
    Path dataDir;

    @BeforeAll
    static void initAll() {
        TestDatabaseFactory.init();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setChannel("chrome"));
    }

    @AfterAll
    static void tearDownAll() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null);
        server = LocalPocketBase.start(config);
        baseUrl = "http://localhost:" + server.port();

        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (server != null) {
            server.close();
        }
    }

    /**
     * Helper: bootstrap superuser and navigate to the main dashboard.
     */
    private void bootstrapAndLogin(String email) {
        page.navigate(baseUrl + "/_/");
        page.waitForSelector("input[type='email']");
        page.fill("input[type='email']", email);
        for (var input : page.querySelectorAll("input[type='password']")) {
            input.fill("password123456");
        }
        page.click("button[type='submit']");
        page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(10000));
    }

    private void createCollectionFromBrowser(String name) {
        page.evaluate("""
                async (name) => {
                  const token = window.localStorage.getItem('pbj_token');
                  const response = await fetch('/api/collections', {
                    method: 'POST',
                    headers: {
                      'Accept': 'application/json',
                      'Authorization': 'Bearer ' + token,
                      'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                      name,
                      fields: [{ name: 'title', type: 'text', required: true }]
                    })
                  });
                  if (!response.ok) {
                    throw new Error(await response.text());
                  }
                }
                """, name);
    }

    private void createRecordCollectionFromBrowser(String name) {
        page.evaluate("""
                async (name) => {
                  const token = window.localStorage.getItem('pbj_token');
                  const headers = {
                    'Accept': 'application/json',
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': 'application/json'
                  };
                  const collection = await fetch('/api/collections', {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({
                      name,
                      fields: [{ name: 'title', type: 'text', required: true }]
                    })
                  });
                  if (!collection.ok) {
                    throw new Error(await collection.text());
                  }
                }
                """, name);
    }

    private String createOAuthAuthCollectionFromBrowser(String name) {
        return String.valueOf(page.evaluate("""
                async (name) => {
                  const token = window.localStorage.getItem('pbj_token');
                  const headers = {
                    'Accept': 'application/json',
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': 'application/json'
                  };
                  const collection = await fetch('/api/collections', {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({
                      name,
                      type: 'auth',
                      fields: [],
                      oauth2: {
                        enabled: true,
                        providers: [{
                          name: 'oidc',
                          clientId: 'client-123',
                          clientSecret: 'secret-456',
                          authURL: 'http://127.0.0.1/authorize',
                          tokenURL: 'http://127.0.0.1/token',
                          userInfoURL: 'http://127.0.0.1/userinfo',
                          scopes: ['openid', 'email'],
                          pkce: true
                        }]
                      }
                    })
                  });
                  if (!collection.ok) {
                    throw new Error(await collection.text());
                  }
                  const record = await fetch(`/api/collections/${encodeURIComponent(name)}/records`, {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({ email: 'oauth-ui@example.com', password: 'password123456' })
                  });
                  if (!record.ok) {
                    throw new Error(await record.text());
                  }
                  return (await record.json()).id;
                }
                """, name));
    }

    private void createOAuthPopupCollectionFromBrowser(String name, FakeOAuth2Server oauth) {
        page.evaluate("""
                async ({ name, baseUrl }) => {
                  const token = window.localStorage.getItem('pbj_token');
                  const headers = {
                    'Accept': 'application/json',
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': 'application/json'
                  };
                  const collection = await fetch('/api/collections', {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({
                      name,
                      type: 'auth',
                      fields: [],
                      oauth2: {
                        enabled: true,
                        providers: [{
                          name: 'oidc',
                          clientId: 'client-123',
                          clientSecret: 'secret-456',
                          authURL: `${baseUrl}/authorize`,
                          tokenURL: `${baseUrl}/token`,
                          userInfoURL: `${baseUrl}/userinfo`,
                          scopes: ['openid', 'email', 'profile'],
                          pkce: true
                        }]
                      }
                    })
                  });
                  if (!collection.ok) {
                    throw new Error(await collection.text());
                  }
                }
                """, Map.of("name", name, "baseUrl", oauth.baseUrl()));
    }

    private void assertHashRoute(String hash, String selector) {
        page.navigate("about:blank");
        page.navigate(baseUrl + "/_/" + hash);
        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(10000));
        assertEquals(hash, page.evaluate("window.location.hash"));
    }

    private void waitForCollectionRoute(String name) {
        page.waitForSelector(
                ".breadcrumbs span[title='" + name + "']",
                new Page.WaitForSelectorOptions().setTimeout(10000)
        );
    }

    @Test
    void testInitialAdminSetupAndLogin() {
        page.navigate(baseUrl + "/_/");
        page.waitForSelector("input[type='email']");
        page.fill("input[type='email']", "admin@example.com");
        page.fill("input[type='password']", "password123456");
        page.click("button[type='submit']");

        // After bootstrap, the dashboard should load
        page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(10000));
        assertTrue(page.content().contains("Collections") || page.content().contains("New collection"));
    }

    @Test
    void testCollectionEditorModalRenders() {
        bootstrapAndLogin("admin2@example.com");

        // Open the collection editor modal
        page.click("button:has-text('New collection')");
        page.waitForSelector(".modal-backdrop", new Page.WaitForSelectorOptions().setTimeout(5000));

        // Verify the modal has the expected structure
        assertTrue(page.content().contains("New collection"), "Modal title should be 'New collection'");

        // Verify the Name input exists
        page.waitForSelector("input[placeholder='posts']", new Page.WaitForSelectorOptions().setTimeout(5000));

        // Verify field type buttons exist (text, number, bool, etc.)
        assertTrue(page.locator("button:has-text('text')").count() > 0, "Should have 'text' field type button");
        assertTrue(page.locator("button:has-text('number')").count() > 0, "Should have 'number' field type button");
        assertTrue(page.locator("button:has-text('bool')").count() > 0, "Should have 'bool' field type button");

        // Close the modal
        page.click(".modal-backdrop button[title='Close']");
        page.waitForSelector(".modal-backdrop", new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.HIDDEN).setTimeout(5000));
    }

    @Test
    void testNavigateToSettings() {
        bootstrapAndLogin("admin3@example.com");

        // Click the Settings header link
        page.click("button.header-link:has-text('Settings')");

        // Settings view shows a settings sidebar and the General settings page
        page.waitForSelector(".settings-sidebar", new Page.WaitForSelectorOptions().setTimeout(5000));
        assertTrue(page.content().contains("General") || page.content().contains("Settings"));
    }

    @Test
    void testCoreHashRoutesRenderAdminWorkflows() {
        bootstrapAndLogin("admin4@example.com");
        createCollectionFromBrowser("ui_hash_posts");

        assertHashRoute("#/collections/ui_hash_posts/schema", ".schema-layout .summary-row:has-text('Type')");
        assertHashRoute("#/collections/ui_hash_posts/records", ".records-page");
        assertHashRoute("#/settings", "#settings-json");
        assertHashRoute("#/settings/mail", "#test-email-recipient");
        assertHashRoute("#/settings/storage", "#s3-enabled");
        assertHashRoute("#/settings/backups", ".backups-surface");
        assertHashRoute("#/settings/crons", ".crons-surface");
        assertHashRoute("#/settings/export-collections", ".export-transfer-surface");
        assertHashRoute("#/settings/import-collections", "#import-collections-json");
        assertHashRoute("#/settings/sql", "#sql-query");
        assertHashRoute("#/logs", ".logs-page");
    }

    @Test
    void testCollectionRecordAndOAuthEditorWorkflows() {
        bootstrapAndLogin("admin5@example.com");
        createRecordCollectionFromBrowser("ui_workflow_posts");

        page.navigate("about:blank");
        page.navigate(baseUrl + "/_/#/collections/ui_workflow_posts/schema");
        page.waitForSelector(".schema-layout", new Page.WaitForSelectorOptions().setTimeout(10000));

        page.click("button:has-text('Edit schema')");
        page.waitForSelector(".modal-backdrop:has-text('Edit ui_workflow_posts')", new Page.WaitForSelectorOptions().setTimeout(5000));
        page.waitForSelector(".field-builder-panel", new Page.WaitForSelectorOptions().setTimeout(5000));
        page.click(".modal-backdrop button:has-text('Rules')");
        page.waitForSelector(".collection-rules-panel textarea", new Page.WaitForSelectorOptions().setTimeout(5000));
        page.click(".modal-backdrop button[title='Close']");
        page.waitForSelector(".modal-backdrop", new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.HIDDEN).setTimeout(5000));

        page.navigate("about:blank");
        page.navigate(baseUrl + "/_/#/collections/ui_workflow_posts/records");
        page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
        waitForCollectionRoute("ui_workflow_posts");
        page.click("button.new-record-btn");
        page.waitForSelector(".record-upsert-form", new Page.WaitForSelectorOptions().setTimeout(5000));
        page.fill("textarea[name='ui_workflow_postsRecordJson']", """
                {
                  "title": "Created from Admin UI"
                }
                """);
        page.click(".record-footer-actions button:has-text('Create')");
        page.waitForSelector(".modal-backdrop", new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.HIDDEN).setTimeout(10000));
        page.navigate("about:blank");
        page.navigate(baseUrl + "/_/#/collections/ui_workflow_posts/records");
        page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
        waitForCollectionRoute("ui_workflow_posts");
        page.waitForSelector("tr:has-text('Created from Admin UI')", new Page.WaitForSelectorOptions().setTimeout(10000));

        page.click("tr:has-text('Created from Admin UI') button[title='Edit']");
        page.waitForSelector(".record-upsert-form", new Page.WaitForSelectorOptions().setTimeout(5000));
        page.fill("textarea[name='ui_workflow_postsRecordJson']", """
                {
                  "title": "Updated from Admin UI"
                }
                """);
        page.click(".record-footer-actions button:has-text('Save changes')");
        page.waitForSelector(".modal-backdrop", new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.HIDDEN).setTimeout(10000));
        page.waitForSelector("tr:has-text('Updated from Admin UI')", new Page.WaitForSelectorOptions().setTimeout(10000));

        createOAuthAuthCollectionFromBrowser("ui_oauth_users");
        page.navigate("about:blank");
        page.navigate(baseUrl + "/_/#/collections/ui_oauth_users/schema");
        page.waitForSelector(".auth-method-card:has-text('OAuth2')", new Page.WaitForSelectorOptions().setTimeout(10000));
        page.waitForSelector(".provider-chip-detailed:has-text('oidc')", new Page.WaitForSelectorOptions().setTimeout(5000));

        page.navigate("about:blank");
        page.navigate(baseUrl + "/_/#/collections/ui_oauth_users/records");
        page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
        waitForCollectionRoute("ui_oauth_users");
        page.waitForSelector("tr:has-text('oauth-ui@example.com')", new Page.WaitForSelectorOptions().setTimeout(10000));
        page.click("tr:has-text('oauth-ui@example.com') button[title='Edit']");
        page.waitForSelector(".record-upsert-form", new Page.WaitForSelectorOptions().setTimeout(5000));
        page.click(".record-modal-tabs button:has-text('Auth providers')");
        page.waitForSelector(".auth-provider-row:has-text('oidc')", new Page.WaitForSelectorOptions().setTimeout(5000));
    }

    @Test
    void testOAuth2PopupTesterCompletesBrowserCallback() throws Exception {
        bootstrapAndLogin("admin6@example.com");
        try (FakeOAuth2Server oauth = FakeOAuth2Server.start()) {
            createOAuthPopupCollectionFromBrowser("ui_oauth_popup_users", oauth);

            page.navigate("about:blank");
            page.navigate(baseUrl + "/_/#/collections/ui_oauth_popup_users/schema");
            page.waitForSelector(".auth-method-card:has-text('OAuth2')", new Page.WaitForSelectorOptions().setTimeout(10000));
            page.waitForSelector(".provider-chip-detailed:has-text('oidc')", new Page.WaitForSelectorOptions().setTimeout(5000));

            page.click(".provider-chip-detailed:has-text('oidc') button:has-text('Test')");
            page.waitForSelector(".modal-backdrop:has-text('OAuth2 Result: OIDC')", new Page.WaitForSelectorOptions().setTimeout(15000));
            page.waitForFunction("""
                    () => Array.from(document.querySelectorAll('.modal-backdrop textarea'))
                      .some((item) => item.value.includes('oidc@example.com') && item.value.includes('"isNew": true'))
                    """);
            assertTrue(oauth.lastTokenBody().contains("code=admin-ui-code"));
            assertTrue(oauth.lastTokenBody().contains("code_verifier="));
        }
    }

    private static final class FakeOAuth2Server implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastTokenBody = new AtomicReference<>("");

        private FakeOAuth2Server(HttpServer server) {
            this.server = server;
        }

        static FakeOAuth2Server start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeOAuth2Server fake = new FakeOAuth2Server(server);
            server.createContext("/authorize", exchange -> {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String redirect = query.getOrDefault("redirect_uri", "");
                String separator = redirect.contains("?") ? "&" : "?";
                String location = redirect + separator + "state=" + query.getOrDefault("state", "") + "&code=admin-ui-code";
                exchange.getResponseHeaders().set("Location", location);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/token", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                fake.lastTokenBody.set(body);
                byte[] bytes = "{\"access_token\":\"token-123\",\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.createContext("/userinfo", exchange -> {
                byte[] bytes = """
                        {
                          "sub":"oauth-sub-123",
                          "email":"oidc@example.com",
                          "name":"OIDC User",
                          "preferred_username":"oidc-user"
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return fake;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String lastTokenBody() {
            return lastTokenBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> values = new LinkedHashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) {
                return values;
            }
            for (String part : rawQuery.split("&")) {
                int index = part.indexOf('=');
                String key = index >= 0 ? part.substring(0, index) : part;
                String value = index >= 0 ? part.substring(index + 1) : "";
                values.put(decode(key), decode(value));
            }
            return values;
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
