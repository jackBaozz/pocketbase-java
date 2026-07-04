package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationPlannerTest {
    @Test
    void detectsFieldAndIndexChangesWithDestructiveMarkers() {
        CollectionSchema current = collection("posts",
                new FieldSchema("f1", "title", "text", true, false, false),
                new FieldSchema("f2", "old_count", "number", false, false, false),
                new FieldSchema("f3", "legacy", "text", false, false, false));
        current.indexes = List.of("CREATE INDEX idx_posts_title ON posts (title)");

        CollectionSchema desired = collection("posts",
                new FieldSchema("f1", "title", "editor", true, false, false),
                new FieldSchema("f2", "count", "number", false, false, false),
                new FieldSchema("f4", "published", "bool", false, false, false));
        desired.indexes = List.of("CREATE INDEX idx_posts_count ON posts (count)");

        var plan = SchemaMigrationPlanner.plan(current, desired, name -> "`" + name + "`");

        assertTrue(plan.stream().anyMatch(op -> "renameField".equals(op.get("type")) && "count".equals(op.get("field"))));
        assertTrue(plan.stream().anyMatch(op -> "addField".equals(op.get("type")) && "published".equals(op.get("field"))));
        assertTrue(plan.stream().anyMatch(op -> "dropField".equals(op.get("type")) && Boolean.TRUE.equals(op.get("destructive"))));
        assertTrue(plan.stream().anyMatch(op -> "alterFieldType".equals(op.get("type")) && Boolean.TRUE.equals(op.get("destructive"))));
        assertTrue(plan.stream().anyMatch(op -> "dropIndex".equals(op.get("type"))));
        assertTrue(plan.stream().anyMatch(op -> "createIndex".equals(op.get("type"))));
    }

    @Test
    void planHandlesNullOrBlankFieldNamesGracefully() {
        CollectionSchema current = collection("posts",
                new FieldSchema("f1", null, "text", true, false, false),
                new FieldSchema("f2", "", "number", false, false, false));

        CollectionSchema desired = collection("posts",
                new FieldSchema("f1", null, "editor", true, false, false),
                new FieldSchema("f2", "   ", "number", false, false, false),
                new FieldSchema("f3", "valid", "text", false, false, false));

        var plan = SchemaMigrationPlanner.plan(current, desired, name -> "`" + name + "`");

        // Should not throw NullPointerException, and only plan operations for valid fields
        assertNotNull(plan);
        assertTrue(plan.stream().anyMatch(op -> "addField".equals(op.get("type")) && "valid".equals(op.get("field"))));
        assertFalse(plan.stream().anyMatch(op -> "dropField".equals(op.get("type"))));
    }

    private CollectionSchema collection(String name, FieldSchema... fields) {
        CollectionSchema schema = new CollectionSchema();
        schema.id = "pbc_" + name;
        schema.name = name;
        schema.type = "base";
        schema.fields = List.of(fields);
        return schema;
    }
}
