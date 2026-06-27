package io.github.jackbaozz.pocketbase.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
