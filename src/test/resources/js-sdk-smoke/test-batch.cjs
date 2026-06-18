const PocketBase = require('pocketbase/cjs');

const pb = new PocketBase('http://127.0.0.1:8090');

// Mock fetch to see what request it generates
global.fetch = async (url, options) => {
    console.log("URL:", url);
    console.log("Method:", options.method);
    console.log("Headers:", options.headers);
    if (options.body && options.body.toString() === '[object FormData]') {
        console.log("FormData entries:");
        for (let pair of options.body.entries()) {
            console.log(pair[0], ':', typeof pair[1], pair[1].name || pair[1]);
        }
    } else {
        console.log("Body:", options.body);
    }
    return {
        ok: true,
        status: 200,
        json: async () => ([])
    };
};

async function testBatch() {
    const batch = pb.createBatch();
    batch.collection("posts").create({ title: "hello" });

    // mock file
    const blob = new Blob(["hello"], { type: "text/plain" });
    blob.name = "test.txt";

    batch.collection("posts").create({ title: "with-file", document: blob });

    await batch.send();
}

testBatch();