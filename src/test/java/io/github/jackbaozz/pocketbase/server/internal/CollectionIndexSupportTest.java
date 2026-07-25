package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionIndexSupportTest {
    @Test
    void normalizesTableNamesAndPreservesExpressions() {
        CollectionSchema collection = collection("posts");
        collection.indexes = List.of(
                "create unique index idx_posts_search on ignored (lower(title), count desc) where count > 0"
        );

        List<String> normalized = CollectionIndexSupport.normalize(collection, List.of(), "Failed.");

        assertEquals(1, normalized.size());
        assertTrue(normalized.get(0).contains("INDEX `idx_posts_search` ON `posts`"));
        assertTrue(normalized.get(0).contains("lower(title), `count` DESC"));
        assertTrue(normalized.get(0).endsWith("WHERE count > 0"));

        String postgresSql = CollectionIndexSupport.createSql(
                "create index idx_quoted on posts (lower(`title`)) where `count` > 0",
                "posts",
                identifier -> "\"" + identifier + "\""
        );
        assertTrue(postgresSql.contains("lower(\"title\")"));
        assertTrue(postgresSql.endsWith("WHERE \"count\" > 0"));
    }

    @Test
    void mysqlUsesBoundedPrefixesForLongTextIndexesWithoutChangingMetadata() {
        CollectionSchema collection = collection("posts");

        String sql = CollectionIndexSupport.createSql(
                "CREATE INDEX idx_posts_title ON posts (title)",
                collection.name,
                identifier -> "`" + identifier + "`",
                JooqDatabase.Engine.MYSQL,
                collection.fields
        );

        assertEquals("CREATE INDEX `idx_posts_title` ON `posts` (`title`(768))", sql);
    }

    @Test
    void mysqlUsesFullValueHashesForLongTextUniqueAndPartialIndexes() {
        CollectionSchema collection = collection("posts");

        String uniqueSql = CollectionIndexSupport.createSql(
                "CREATE UNIQUE INDEX idx_posts_title_unique ON posts (title)",
                collection.name,
                identifier -> "`" + identifier + "`",
                JooqDatabase.Engine.MYSQL,
                collection.fields
        );
        assertEquals(
                "CREATE UNIQUE INDEX `idx_posts_title_unique` ON `posts` ((UNHEX(SHA2(`title`, 256))))",
                uniqueSql
        );
        assertFalse(uniqueSql.contains("`title`(768)"));

        String partialSql = CollectionIndexSupport.createSql(
                "CREATE UNIQUE INDEX idx_posts_title_present ON posts (title) WHERE title != ''",
                collection.name,
                identifier -> "`" + identifier + "`",
                JooqDatabase.Engine.MYSQL,
                collection.fields
        );
        assertEquals(
                "CREATE UNIQUE INDEX `idx_posts_title_present` ON `posts` "
                        + "((CASE WHEN title != '' THEN UNHEX(SHA2(`title`, 256)) ELSE NULL END))",
                partialSql
        );
        assertFalse(partialSql.contains("`title`(768)"));
    }

    @Test
    void rejectsDuplicateDefinitionsAndExternalNames() {
        CollectionSchema duplicated = collection("posts");
        duplicated.indexes = List.of(
                "create index idx_posts_title_a on posts (title)",
                "create index idx_posts_title_b on posts (title)"
        );
        ApiException duplicateError = assertThrows(
                ApiException.class,
                () -> CollectionIndexSupport.normalize(duplicated, List.of(), "Failed.")
        );
        assertTrue(String.valueOf(duplicateError.data()).contains("validation_duplicated_index_definition"));

        CollectionSchema external = collection("posts");
        external.indexes = List.of("create index IDX_SHARED on posts (title)");
        ApiException externalError = assertThrows(
                ApiException.class,
                () -> CollectionIndexSupport.normalize(external, List.of("idx_shared"), "Failed.")
        );
        assertTrue(String.valueOf(externalError.data()).contains("validation_existing_index_name"));
    }

    @Test
    void rejectsIndexesForViews() {
        CollectionSchema view = collection("post_view");
        view.type = "view";
        view.indexes = List.of("create index idx_view_title on post_view (title)");

        ApiException error = assertThrows(
                ApiException.class,
                () -> CollectionIndexSupport.normalize(view, List.of(), "Failed.")
        );

        assertTrue(String.valueOf(error.data()).contains("validation_indexes_not_supported"));
    }

    private CollectionSchema collection(String name) {
        CollectionSchema collection = new CollectionSchema();
        collection.name = name;
        collection.type = "base";
        collection.fields = new ArrayList<>(List.of(
                new FieldSchema("field_title", "title", "text", false, false, false),
                new FieldSchema("field_count", "count", "number", false, false, false)
        ));
        return collection;
    }
}
