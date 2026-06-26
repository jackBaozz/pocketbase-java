package io.github.jackbaozz.pocketbase.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void testInitialAdminSetupAndLogin() {
        page.navigate(baseUrl + "/_/");
        // PocketBase admin UI setup screen
        page.waitForSelector("input[type='email']");
        
        // Fill the setup form
        page.fill("input[type='email']", "admin@example.com");
        // We might have multiple password inputs in PocketBase initial setup. Let's find them by ID if possible, or just fill them all.
        // In PocketBase v0.22, the fields are #password and #passwordConfirm
        page.fill("input[type='password']", "password123456");
        
        try {
            page.fill("input[name='passwordConfirm']", "password123456");
        } catch (Exception e) {
            // fallback
            for (var input : page.querySelectorAll("input[type='password']")) {
                input.fill("password123456");
            }
        }
        
        // Click Create button. Usually it has text "Create". Or we can just click "button[type='submit']"
        page.click("button[type='submit']");
        
        // Wait for dashboard to load (usually "Collections" header or similar)
        page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(5000));
        assertTrue(page.content().contains("Collections") || page.content().contains("New collection"));
    }

    @Test
    void testCreateCollectionAndRecord() {
        // Setup initial admin and login
        page.navigate(baseUrl + "/_/");
        page.waitForSelector("input[type='email']");
        page.fill("input[type='email']", "admin2@example.com");
        for (var input : page.querySelectorAll("input[type='password']")) {
            input.fill("password123456");
        }
        page.click("button[type='submit']");
        page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(5000));

        // Create a new collection
        page.click("button:has-text('New collection')");
        page.getByLabel("Name", new Page.GetByLabelOptions().setExact(true)).fill("test_collection");
        
        // Add a text field
        page.click("button:has-text('New field')");
        page.click("button:has-text('Text')");
        page.waitForSelector("input[name='fields.1.name']");
        page.fill("input[name='fields.1.name']", "title");

        // Save collection
        page.click("button:has-text('Create')");
        // Wait for success toast or modal to close
        page.waitForSelector(".toast-success", new Page.WaitForSelectorOptions().setTimeout(5000));

        // Create a new record
        page.click("button:has-text('New record')");
        page.waitForSelector("input[name='title']");
        page.fill("input[name='title']", "Hello Playwright");
        page.click("button:has-text('Create')");
        
        // Wait for record to appear
        page.waitForSelector("td:has-text('Hello Playwright')");
        assertTrue(page.content().contains("Hello Playwright"));
    }

    @Test
    void testNavigateToSettings() {
        // Setup initial admin and login
        page.navigate(baseUrl + "/_/");
        page.waitForSelector("input[type='email']");
        page.fill("input[type='email']", "admin3@example.com");
        for (var input : page.querySelectorAll("input[type='password']")) {
            input.fill("password123456");
        }
        page.click("button[type='submit']");
        page.waitForSelector(".page-header", new Page.WaitForSelectorOptions().setTimeout(5000));

        // Navigate to settings
        page.click("button.header-link:has-text('Settings')");
        page.waitForSelector("h1:has-text('Application')", new Page.WaitForSelectorOptions().setTimeout(5000));
        assertTrue(page.content().contains("Application URL") || page.content().contains("Application"));
    }
}
