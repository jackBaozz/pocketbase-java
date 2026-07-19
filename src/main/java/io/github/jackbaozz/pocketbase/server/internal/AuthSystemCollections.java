package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defines the PocketBase authentication support collections exposed through the Records API.
 */
public final class AuthSystemCollections {
    public static final String AUTH_ORIGINS = SystemCollections.AUTH_ORIGINS;
    public static final String EXTERNAL_AUTHS = SystemCollections.EXTERNAL_AUTHS;
    public static final String MFAS = SystemCollections.MFAS;
    public static final String OTPS = SystemCollections.OTPS;

    public static final String AUTH_ORIGINS_ID = SystemCollections.AUTH_ORIGINS_ID;
    public static final String EXTERNAL_AUTHS_ID = SystemCollections.EXTERNAL_AUTHS_ID;
    public static final String MFAS_ID = SystemCollections.MFAS_ID;
    public static final String OTPS_ID = SystemCollections.OTPS_ID;

    public static final String OWNER_RULE =
            "@request.auth.id != '' && recordRef = @request.auth.id && collectionRef = @request.auth.collectionId";

    private static final Set<String> NAMES = Set.of(AUTH_ORIGINS, EXTERNAL_AUTHS, MFAS, OTPS);
    private static final Set<String> MANAGED_FIELDS = Set.of("id", "created", "updated");

    private AuthSystemCollections() {
    }

    public static List<CollectionSchema> defaults() {
        return List.of(
                authOrigins(),
                externalAuths(),
                mfas(),
                otps()
        );
    }

    public static CollectionSchema superusers() {
        CollectionSchema schema = new CollectionSchema();
        schema.id = SystemCollections.SUPERUSERS_ID;
        schema.name = SystemCollections.SUPERUSERS;
        schema.type = "auth";
        schema.system = true;
        schema.authToken.duration = 86_400L;
        schema.fields = new ArrayList<>();
        AuthCollectionFields.normalize(schema);
        addAutodates(schema);
        return schema;
    }

    public static boolean contains(String collectionName) {
        return NAMES.contains(collectionName);
    }

    public static void applySaveInvariants(CollectionSchema schema) {
        if (schema == null || !SystemCollections.SUPERUSERS.equals(schema.name)) {
            return;
        }
        schema.passwordAuth.enabled = true;
        schema.oauth2.enabled = false;
        schema.oauth2.providers.clear();
        if (schema.otp.enabled) {
            schema.mfa.enabled = true;
        }
    }

    public static boolean isManagedField(String fieldName) {
        return MANAGED_FIELDS.contains(fieldName);
    }

    public static void normalizeLegacyRecord(String collectionName, Map<String, Object> record) {
        if (!contains(collectionName) || record == null) {
            return;
        }
        moveIfMissing(record, "collectionRef", "collectionId");
        moveIfMissing(record, "recordRef", "recordId");
        if (OTPS.equals(collectionName)) {
            moveIfMissing(record, "password", "passwordHash");
        }
        record.remove("collectionName");
    }

    private static CollectionSchema authOrigins() {
        CollectionSchema schema = base(AUTH_ORIGINS_ID, AUTH_ORIGINS);
        schema.listRule = OWNER_RULE;
        schema.viewRule = OWNER_RULE;
        schema.deleteRule = OWNER_RULE;
        schema.fields.add(text("text455797646", "collectionRef", true));
        schema.fields.add(text("text127846527", "recordRef", true));
        schema.fields.add(text("text4228609354", "fingerprint", true));
        schema.indexes = new ArrayList<>(List.of(
                "CREATE UNIQUE INDEX idx_authOrigins_unique_pairs ON _authOrigins (collectionRef, recordRef, fingerprint)"
        ));
        addAutodates(schema);
        return schema;
    }

    private static CollectionSchema externalAuths() {
        CollectionSchema schema = base(EXTERNAL_AUTHS_ID, EXTERNAL_AUTHS);
        schema.listRule = OWNER_RULE;
        schema.viewRule = OWNER_RULE;
        schema.deleteRule = OWNER_RULE;
        schema.fields.add(text("text455797646", "collectionRef", true));
        schema.fields.add(text("text127846527", "recordRef", true));
        schema.fields.add(text("text2462348188", "provider", true));
        schema.fields.add(text("text1044722854", "providerId", true));
        schema.indexes = new ArrayList<>(List.of(
                "CREATE UNIQUE INDEX idx_externalAuths_record_provider ON _externalAuths (collectionRef, recordRef, provider)",
                "CREATE UNIQUE INDEX idx_externalAuths_collection_provider ON _externalAuths (collectionRef, provider, providerId)"
        ));
        addAutodates(schema);
        return schema;
    }

    private static CollectionSchema mfas() {
        CollectionSchema schema = base(MFAS_ID, MFAS);
        schema.listRule = OWNER_RULE;
        schema.viewRule = OWNER_RULE;
        schema.fields.add(text("text455797646", "collectionRef", true));
        schema.fields.add(text("text127846527", "recordRef", true));
        schema.fields.add(text("text1582905952", "method", true));
        schema.indexes = new ArrayList<>(List.of(
                "CREATE INDEX idx_mfas_collectionRef_recordRef ON _mfas (collectionRef, recordRef)"
        ));
        addAutodates(schema);
        return schema;
    }

    private static CollectionSchema otps() {
        CollectionSchema schema = base(OTPS_ID, OTPS);
        schema.listRule = OWNER_RULE;
        schema.viewRule = OWNER_RULE;
        schema.fields.add(text("text455797646", "collectionRef", true));
        schema.fields.add(text("text127846527", "recordRef", true));
        FieldSchema password = new FieldSchema("password901924565", "password", "password", true, false, true);
        password.system = true;
        schema.fields.add(password);
        FieldSchema sentTo = text("text3866985172", "sentTo", false);
        sentTo.hidden = true;
        schema.fields.add(sentTo);
        schema.indexes = new ArrayList<>(List.of(
                "CREATE INDEX idx_otps_collectionRef_recordRef ON _otps (collectionRef, recordRef)"
        ));
        addAutodates(schema);
        return schema;
    }

    private static CollectionSchema base(String id, String name) {
        CollectionSchema schema = new CollectionSchema();
        schema.id = id;
        schema.name = name;
        schema.type = "base";
        schema.system = true;
        schema.fields = new ArrayList<>();
        FieldSchema idField = text("text3208210256", "id", true);
        schema.fields.add(idField);
        return schema;
    }

    private static void addAutodates(CollectionSchema schema) {
        FieldSchema created = new FieldSchema("autodate2990389176", "created", "autodate", false, false, false);
        created.system = true;
        FieldSchema updated = new FieldSchema("autodate3332085495", "updated", "autodate", false, false, false);
        updated.system = true;
        schema.fields.add(created);
        schema.fields.add(updated);
    }

    private static FieldSchema text(String id, String name, boolean required) {
        FieldSchema field = new FieldSchema(id, name, "text", required, false, false);
        field.system = true;
        return field;
    }

    private static void moveIfMissing(Map<String, Object> record, String target, String legacy) {
        if (!record.containsKey(target) && record.containsKey(legacy)) {
            record.put(target, record.get(legacy));
        }
        record.remove(legacy);
    }
}
