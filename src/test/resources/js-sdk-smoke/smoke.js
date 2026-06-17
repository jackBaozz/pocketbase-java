import fetch from 'node-fetch';
import PocketBase from 'pocketbase';

// Since Node 16+, global fetch is usually available, but we use node-fetch to be safe if running on older nodes
if (!globalThis.fetch) {
    globalThis.fetch = fetch;
}

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
        const authData = await pb.admins.authWithPassword('smoke@example.com', 'password123');
        console.log(`Authenticated as: ${authData.admin.email}`);

        // 3. Create a collection
        console.log("Creating collection...");
        try {
            await pb.collections.create({
                name: "smoke_test_collection",
                type: "base",
                schema: [
                    { name: "title", type: "text" },
                    { name: "count", type: "number" }
                ]
            });
        } catch (e) {
            // Ignore if already exists
            if (e.status !== 400) {
                throw e;
            }
        }

        // 4. Create a record
        console.log("Creating record...");
        const record = await pb.collection('smoke_test_collection').create({
            title: "Hello JS SDK",
            count: 42
        });
        console.log(`Created record: ${record.id}`);

        // 5. Fetch records
        console.log("Fetching records...");
        const list = await pb.collection('smoke_test_collection').getList(1, 50, {
            filter: 'count > 40'
        });
        console.log(`Found ${list.items.length} records`);

        if (list.items.length === 0) {
            throw new Error("Failed to find created record");
        }

        // 6. Delete record
        console.log(`Deleting record... ${record.id}`);
        await pb.collection('smoke_test_collection').delete(record.id);

        console.log("✅ JS SDK Smoke Test Passed!");
        process.exit(0);

    } catch (err) {
        console.error("❌ JS SDK Smoke Test Failed:", err);
        process.exit(1);
    }
}

run();