import fetch from 'node-fetch';
import PocketBase from 'pocketbase';
import EventSource from 'eventsource';

// Since Node 16+, global fetch is usually available, but we use node-fetch to be safe if running on older nodes
if (!globalThis.fetch) {
    globalThis.fetch = fetch;
}
globalThis.EventSource = EventSource;

async function run() {
    const args = process.argv.slice(2);
    if (args.length < 1) {
        console.error("Usage: node smoke.js <baseUrl>");
        process.exit(1);
    }

    const baseUrl = args[0];
    console.log(`Starting JS SDK smoke test against: ${baseUrl}`);

    const pb = new PocketBase(baseUrl);

    try {
        // 1. Create superuser via bootstrap (custom route)
        console.log("Bootstrapping superuser...");
        const bootstrapRes = await fetch(`${baseUrl}/api/bootstrap/superuser`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: 'smoke@example.com', password: 'password123' })
        });

        if (!bootstrapRes.ok && bootstrapRes.status !== 400) { // 400 if already bootstrapped
            throw new Error(`Failed to bootstrap: ${await bootstrapRes.text()}`);
        }

        // 2. Auth with password
        console.log("Authenticating...");
        const authData = await pb.collection('_superusers').authWithPassword('smoke@example.com', 'password123');
        console.log(`Authenticated as: ${authData.record.email}`);

        if (!pb.authStore.isValid) {
            throw new Error("SDK auth store rejected the returned auth token");
        }
        if (!pb.authStore.isSuperuser) {
            throw new Error("SDK auth store did not recognize the login as a superuser");
        }

        // 3. Testing auth refresh
        console.log("Testing auth refresh...");
        const refreshData = await pb.collection('_superusers').authRefresh();
        if (!pb.authStore.isValid) {
            throw new Error("Token refresh invalidated the authStore");
        }

        console.log("Testing logout...");
        pb.authStore.clear();
        if (pb.authStore.isValid) {
            throw new Error("AuthStore clear did not log out the user");
        }

        // Re-authenticate for subsequent steps
        await pb.collection('_superusers').authWithPassword('smoke@example.com', 'password123');

        // 4. Create a collection with rules allowing guest CRUD and including a file field
        console.log("Creating collection...");
        try {
            await pb.collections.create({
                name: "smoke_test_collection",
                type: "base",
                createRule: "",
                listRule: "",
                viewRule: "",
                updateRule: "",
                deleteRule: "",
                schema: [
                    { name: "title", type: "text", required: true },
                    { name: "count", type: "number" },
                    { name: "avatar", type: "file", options: { maxSelect: 1, maxSize: 5242880 } }
                ]
            });
        } catch (e) {
            // Ignore if already exists, but we update rules to be sure
            if (e.status !== 400) {
                throw e;
            }
        }

        // 5. Testing file upload & download & token
        console.log("Testing file upload...");
        const fileBlob = new Blob(["hello world"], { type: 'text/plain' });
        const fileRecord = await pb.collection('smoke_test_collection').create({
            title: "File Upload Test",
            count: 100,
            avatar: fileBlob
        });
        console.log(`File record created: ${fileRecord.id}, avatar: ${fileRecord.avatar}`);

        console.log("Testing file download URL...");
        const fileUrl = pb.files.getUrl(fileRecord, fileRecord.avatar);
        console.log(`Downloading from URL: ${fileUrl}`);
        const fileDlRes = await fetch(fileUrl);
        if (!fileDlRes.ok) {
            throw new Error(`Failed to download uploaded file: ${fileDlRes.statusText}`);
        }
        const fileText = await fileDlRes.text();
        if (fileText !== "hello world") {
            throw new Error(`File content mismatch: expected "hello world", got "${fileText}"`);
        }

        console.log("Testing protected file token...");
        const fileToken = await pb.files.getToken();
        if (!fileToken) {
            throw new Error("Failed to get protected file token");
        }

        // 6. Testing batch CRUD & rollback
        console.log("Testing batch create...");
        const batch = pb.createBatch();
        batch.collection('smoke_test_collection').create({ title: "Batch 1", count: 1 });
        batch.collection('smoke_test_collection').create({ title: "Batch 2", count: 2 });
        const batchResult = await batch.send();
        console.log("Batch Result is:", JSON.stringify(batchResult));
        if (!batchResult || !batchResult.responses || batchResult.responses.length !== 2) {
            throw new Error(`Batch send returned invalid responses, expected 2`);
        }

        console.log("Testing batch rollback on failure...");
        const beforeList = await pb.collection('smoke_test_collection').getList(1, 50);
        const beforeCount = beforeList.items.length;

        const failBatch = pb.createBatch();
        failBatch.collection('smoke_test_collection').create({ title: "Rollback Item", count: 1000 });
        failBatch.collection('non_existent_collection').create({ name: "error" });

        try {
            await failBatch.send();
            throw new Error("Batch with error should have failed");
        } catch (e) {
            console.log("Batch failed as expected, checking rollback...");
            const afterList = await pb.collection('smoke_test_collection').getList(1, 50);
            if (afterList.items.length !== beforeCount) {
                throw new Error(`Batch failure did not rollback. Records changed from ${beforeCount} to ${afterList.items.length}`);
            }
            console.log("Batch rollback verified!");
        }

        // 7. Testing realtime SSE
        console.log("Testing realtime SSE...");
        let realtimeEventReceived = false;
        await pb.collection('smoke_test_collection').subscribe('*', (e) => {
            console.log(`[Realtime Event] Action: ${e.action}, Record: ${e.record.id}`);
            if (e.action === 'create' && e.record.title === "Realtime Test") {
                realtimeEventReceived = true;
            }
        });

        await pb.collection('smoke_test_collection').create({
            title: "Realtime Test",
            count: 999
        });

        // Wait for event delivery
        for (let i = 0; i < 30; i++) {
            if (realtimeEventReceived) {
                break;
            }
            await new Promise(r => setTimeout(r, 100));
        }

        if (!realtimeEventReceived) {
            throw new Error("Realtime SSE event not received");
        }
        console.log("Realtime SSE event verified!");
        pb.collection('smoke_test_collection').unsubscribe('*');

        // Cleanup
        console.log("Cleaning up created records...");
        const finalCheckList = await pb.collection('smoke_test_collection').getList(1, 50);
        for (const item of finalCheckList.items) {
            await pb.collection('smoke_test_collection').delete(item.id);
        }

        console.log("✅ JS SDK Smoke Test Passed!");
        process.exit(0);

    } catch (err) {
        console.error("❌ JS SDK Smoke Test Failed:", err);
        process.exit(1);
    }
}

run();
