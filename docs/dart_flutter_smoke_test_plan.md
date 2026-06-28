# Dart/Flutter Smoke Test Plan

## Overview
As we continue to match parity with the official PocketBase server, we need to ensure our Java backend is fully compatible with the official Dart/Flutter SDK (`pocketbase: ^0.19.0` or similar based on PocketBase v0.22.x baseline).

Since a Dart environment may not be available in all build systems (like the current backend CI), we provide this placeholder/document outlining what the test should look like once Dart is installed.

## Prerequisites
- Dart SDK >= 3.0.0
- `pocketbase` pub package

## Test Cases

1. **Authentication:**
   - Bootstrapping a superuser
   - Authenticating via `authWithPassword`
   - Checking `pb.authStore.isValid`
   - Testing auth token refresh with `authRefresh`

2. **Collections & Records:**
   - Creating a test collection dynamically via `pb.collections.create()`
   - Ensuring `createRule` and `listRule` are handled appropriately
   - Testing CRUD on the collection (create, getList, update, delete)

3. **Files:**
   - Uploading a multipart file (e.g. `http.MultipartFile.fromString('avatar', 'hello world', filename: 'test.txt')`)
   - Verifying the file URL `pb.files.getUrl()`
   - Downloading the file and comparing content
   - Verifying protected file token creation `pb.files.getToken()`

4. **Realtime / SSE:**
   - Subscribing to `*` or specific collection with `pb.collection('coll').subscribe()`
   - Ensuring stream yields Create/Update/Delete events correctly
   - Testing unsubscribe

5. **Batch Processing:**
   - Testing `pb.createBatch()`
   - Submitting multiple creates and validating they commit
   - Testing failure scenario to ensure the Java backend rolls back all batched statements

## Execution Model

Ideally, we create a script `src/test/resources/dart-sdk-smoke/smoke.dart` and wrap it in a Java `@Test` class `DartSdkSmokeTest.java`, similar to `JsSdkSmokeTest.java`. The Java code will check for `dart --version` on the PATH and skip the test using `Assume.assumeTrue()` if it is not present.

```java
@Test
void testDartSdkCompatibility() throws Exception {
    ProcessBuilder dartCheck = new ProcessBuilder("dart", "--version");
    try {
        if (dartCheck.start().waitFor() != 0) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Dart not found on PATH");
        }
    } catch (IOException e) {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "Dart not found on PATH");
    }

    // execute dart run smoke.dart <baseUrl>
    // assert output
}
```
