package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIdSupportTest {
    @Test
    void generatesOfficialCrc32IdsAndCollisionSuffixes() {
        assertEquals("pbc_1125843985", IdGenerator.collectionId("base", "posts"));
        assertEquals("text724990059", IdGenerator.fieldId("text", "title"));
        assertEquals(
                "pbc_11258439852",
                SchemaIdSupport.nextCollectionId("base", "posts", "pbc_1125843985"::equals)
        );

        List<FieldSchema> fields = new ArrayList<>(List.of(
                new FieldSchema("text724990059", "legacy", "text", false, false, false),
                new FieldSchema(null, "title", "text", false, false, false)
        ));
        SchemaIdSupport.assignMissingFieldIds(fields, List.of());
        assertEquals("text7249900592", fields.get(1).id);
    }

    @Test
    void preservesExistingFieldIdsByNameAndAddsTheBaseIdField() {
        FieldSchema previous = new FieldSchema("legacy_random_id", "title", "text", false, false, false);
        List<FieldSchema> fields = new ArrayList<>(List.of(
                new FieldSchema(null, "title", "text", false, false, false)
        ));

        SchemaIdSupport.assignMissingFieldIds(fields, List.of(previous));
        SchemaIdSupport.ensureBaseIdField(fields);

        assertEquals("text3208210256", fields.get(0).id);
        assertTrue(fields.get(0).system);
        assertEquals("legacy_random_id", fields.get(1).id);
    }
}
