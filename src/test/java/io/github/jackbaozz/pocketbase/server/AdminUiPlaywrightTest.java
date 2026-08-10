package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * Playwright-based smoke tests for Admin UI rendering and navigation. Full CRUD workflows are
 * covered by JsSdkSmokeTest and BehaviorFixturesTest.
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
    playwright =
        Playwright.create(
            new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    browser = launchBrowser();
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
    ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null, null);
    server = TestDatabaseFactory.start(config);
    baseUrl = "http://localhost:" + server.port();

    context = browser.newContext(new Browser.NewContextOptions().setLocale("en-US"));
    context.addInitScript("window.localStorage.setItem('i18nextLng', 'en');");
    page = context.newPage();
  }

  private static Browser launchBrowser() {
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return playwright
            .chromium()
            .launch(new BrowserType.LaunchOptions().setHeadless(true).setChannel("chrome"));
      } catch (RuntimeException e) {
        lastFailure = e;
        try {
          Thread.sleep(500L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new TestAbortedException(
              "Interrupted while launching Chrome for Admin UI Playwright tests.", interrupted);
        }
      }
    }
    try {
      return playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    } catch (RuntimeException e) {
      if (lastFailure != null) {
        e.addSuppressed(lastFailure);
      }
      throw new TestAbortedException(
          "Chrome/Chromium is unavailable for Admin UI Playwright tests.", e);
    }
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

  /** Helper: bootstrap superuser and navigate to the main dashboard. */
  private void bootstrapAndLogin(String email) {
    page.navigate(baseUrl + "/_/");
    page.waitForSelector("input[type='email']");
    page.fill("input[type='email']", email);
    for (var input : page.querySelectorAll("input[type='password']")) {
      input.fill("Password_123456");
    }
    page.click("button[type='submit']");
    page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(10000));
    // The dashboard starts several collection/settings requests after authentication. Wait until
    // those reads settle before the test sends DDL through the browser, otherwise SQLite schema
    // writes can race the initial Admin UI requests on a loaded CI runner.
    page.waitForLoadState(
        LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
  }

  private void createCollectionFromBrowser(String name) {
    page.evaluate(
        """
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
            """,
        name);
  }

  private void createRecordCollectionFromBrowser(String name) {
    page.evaluate(
        """
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
            """,
        name);
  }

  private String createOAuthAuthCollectionFromBrowser(String name) {
    return String.valueOf(
        page.evaluate(
            """
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
                    body: JSON.stringify({
                      email: 'oauth-ui@example.com',
                      password: 'Password_123456',
                      passwordConfirm: 'Password_123456'
                    })
                  });
                  if (!record.ok) {
                    throw new Error(await record.text());
                  }
                  return (await record.json()).id;
                }
                """,
            name));
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
        new Page.WaitForSelectorOptions().setTimeout(10000));
  }

  @Test
  void testInitialAdminSetupAndLogin() {
    page.navigate(baseUrl + "/_/");
    page.waitForSelector("input[type='email']");
    page.fill("input[type='email']", "admin@example.com");
    page.fill("input[type='password']", "Password_123456");
    page.click("button[type='submit']");

    // After bootstrap, the dashboard should load
    page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(10000));
    assertTrue(page.content().contains("Collections") || page.content().contains("New collection"));
  }

  @Test
  void testCollectionEditorDrawerRenders() {
    bootstrapAndLogin("admin2@example.com");

    // The collection editor is a right-side drawer in the current Admin UI.
    page.click("button:has-text('New collection')");
    page.waitForSelector(".drawer-backdrop", new Page.WaitForSelectorOptions().setTimeout(5000));

    page.waitForSelector(
        ".drawer-panel[aria-label='New Collection']",
        new Page.WaitForSelectorOptions().setTimeout(5000));

    // Verify the Name input exists
    page.waitForSelector(
        "input[placeholder='posts']", new Page.WaitForSelectorOptions().setTimeout(5000));

    // Verify field type buttons exist (text, number, bool, etc.)
    assertTrue(
        page.locator("button:has-text('text')").count() > 0,
        "Should have 'text' field type button");
    assertTrue(
        page.locator("button:has-text('number')").count() > 0,
        "Should have 'number' field type button");
    assertTrue(
        page.locator("button:has-text('bool')").count() > 0,
        "Should have 'bool' field type button");

    // Close the modal
    page.click(".drawer-backdrop button[title='Close']");
    page.waitForSelector(
        ".drawer-backdrop",
        new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(5000));
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

    // Legacy schema links intentionally fall back to the records view; schema editing now lives
    // in the collection settings drawer opened from the records toolbar.
    assertHashRoute("#/collections/ui_hash_posts/schema", ".records-page");
    assertHashRoute("#/collections/ui_hash_posts/records", ".records-page");
    assertHashRoute("#/settings", ".application-settings-footer");
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
    page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));

    page.click("button[aria-label='Collection settings']");
    page.waitForSelector(
        ".drawer-backdrop:has-text('Edit ui_workflow_posts')",
        new Page.WaitForSelectorOptions().setTimeout(5000));
    page.waitForSelector(
        ".field-builder-panel", new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(".collection-modal-tabs button:has-text('API rules')");
    // Rules start locked (superusers only, i.e. null) and only expose an editor once unlocked,
    // which is what keeps a null rule from being saved back as an empty "public" rule.
    page.waitForSelector(
        ".collection-rules-panel .rule-field.locked",
        new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(
        ".collection-rules-panel .rule-field.locked button:has-text('Unlock and set custom rule')");
    page.waitForSelector(
        ".collection-rules-panel textarea", new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(".drawer-backdrop button[title='Close']");
    // Unlocking is an unsaved change, so closing asks for confirmation first.
    page.waitForSelector(".confirm-dialog", new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(".confirm-actions button.danger");
    page.waitForSelector(
        ".drawer-backdrop",
        new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(5000));

    page.navigate("about:blank");
    page.navigate(baseUrl + "/_/#/collections/ui_workflow_posts/records");
    page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
    waitForCollectionRoute("ui_workflow_posts");
    page.click("button.new-record-btn");
    page.waitForSelector(".record-upsert-form", new Page.WaitForSelectorOptions().setTimeout(5000));
    page.fill(
        "textarea[name='ui_workflow_postsRecordJson']",
        """
            {
              "title": "Created from Admin UI"
            }
            """);
    page.click(".record-footer-actions button:has-text('Create')");
    page.waitForSelector(
        ".drawer-backdrop",
        new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(10000));
    page.navigate("about:blank");
    page.navigate(baseUrl + "/_/#/collections/ui_workflow_posts/records");
    page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
    waitForCollectionRoute("ui_workflow_posts");
    page.waitForSelector(
        "tr:has-text('Created from Admin UI')",
        new Page.WaitForSelectorOptions().setTimeout(10000));

    // Rows are the edit affordance in the current records table; the former per-row Edit button
    // was removed when the table adopted keyboard/row navigation.
    page.click("tr:has-text('Created from Admin UI')");
    page.waitForSelector(".record-upsert-form", new Page.WaitForSelectorOptions().setTimeout(5000));
    page.fill(
        "textarea[name='ui_workflow_postsRecordJson']",
        """
            {
              "title": "Updated from Admin UI"
            }
            """);
    page.click(".record-footer-actions button:has-text('Save changes')");
    page.waitForSelector(
        ".drawer-backdrop",
        new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(10000));
    page.waitForSelector(
        "tr:has-text('Updated from Admin UI')",
        new Page.WaitForSelectorOptions().setTimeout(10000));

    createOAuthAuthCollectionFromBrowser("ui_oauth_users");
    page.navigate("about:blank");
    page.navigate(baseUrl + "/_/#/collections/ui_oauth_users/schema");
    page.waitForSelector(
        ".records-page",
        new Page.WaitForSelectorOptions().setTimeout(10000));
    page.click("button[aria-label='Collection settings']");
    page.waitForSelector(
        ".drawer-panel[aria-label='Edit ui_oauth_users']",
        new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(".collection-modal-tabs button:has-text('Options')");
    page.waitForSelector(
        ".auth-config-card-wide",
        new Page.WaitForSelectorOptions().setTimeout(5000));
    page.waitForSelector(
        ".oauth-provider-config-card:has-text('OIDC')",
        new Page.WaitForSelectorOptions().setTimeout(5000));

    page.navigate("about:blank");
    page.navigate(baseUrl + "/_/#/collections/ui_oauth_users/records");
    page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
    waitForCollectionRoute("ui_oauth_users");
    page.waitForSelector(
        "tr:has-text('oauth-ui@example.com')", new Page.WaitForSelectorOptions().setTimeout(10000));
    page.click("tr:has-text('oauth-ui@example.com')");
    page.waitForSelector(".record-upsert-form", new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(".record-modal-tabs button:has-text('Auth providers')");
    page.waitForSelector(
        ".auth-provider-row:has-text('oidc')", new Page.WaitForSelectorOptions().setTimeout(5000));
  }

  @Test
  void testOAuth2CollectionEditorShowsProviderConfiguration() {
    bootstrapAndLogin("admin6@example.com");
    createOAuthAuthCollectionFromBrowser("ui_oauth_popup_users");

    page.navigate("about:blank");
    page.navigate(baseUrl + "/_/#/collections/ui_oauth_popup_users/schema");
    page.waitForSelector(".records-page", new Page.WaitForSelectorOptions().setTimeout(10000));
    page.click("button[aria-label='Collection settings']");
    page.waitForSelector(
        ".drawer-panel[aria-label='Edit ui_oauth_popup_users']",
        new Page.WaitForSelectorOptions().setTimeout(5000));
    page.click(".collection-modal-tabs button:has-text('Options')");
    page.waitForSelector(
        ".oauth-provider-config-card:has-text('OIDC')",
        new Page.WaitForSelectorOptions().setTimeout(5000));
    page.waitForSelector(
        ".oidc-discovery-assistant",
        new Page.WaitForSelectorOptions().setTimeout(5000));
  }

}
