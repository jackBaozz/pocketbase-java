package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordFieldResolverSupportTest {

    @Test
    void resolvesRecordBodyAuthAndBackRelationPaths() {
        CollectionSchema teams = collection("teams_id", "teams", field("name", "text", null, 1, false));
        CollectionSchema users = collection(
                "users_id",
                "users",
                field("name", "text", null, 1, false),
                field("secret", "text", null, 1, true),
                field("team", "relation", teams.id, 1, false)
        );
        users.type = "auth";
        CollectionSchema posts = collection(
                "posts_id",
                "posts",
                field("title", "text", null, 1, false),
                field("author", "relation", users.id, 1, false),
                field("reviewers", "relation", users.id, 3, false)
        );

        Map<String, Object> math = Map.of("id", "team_math", "name", "Math");
        Map<String, Object> ada = Map.of(
                "id", "user_ada",
                "name", "Ada",
                "secret", "hidden",
                "team", "team_math"
        );
        Map<String, Object> grace = Map.of(
                "id", "user_grace",
                "name", "Grace",
                "secret", "hidden-2",
                "team", "team_math"
        );
        Map<String, Object> post = Map.of(
                "id", "post_1",
                "title", "Resolver",
                "author", "user_ada",
                "reviewers", List.of("user_ada", "user_grace")
        );
        TestStore store = new TestStore(
                List.of(teams, users, posts),
                Map.of(
                        teams.id, List.of(math),
                        users.id, List.of(ada, grace),
                        posts.id, List.of(post)
                )
        );
        RequestPrincipal principal = RequestPrincipal.fromClaims(Map.of(
                "sub", "user_ada",
                "collectionId", users.id,
                "collectionName", users.name
        ));

        RuleEvaluator.Context context = RecordFieldResolverSupport.context(
                store,
                posts,
                post,
                Map.of("author", "user_ada"),
                Map.of(),
                "PATCH",
                principal,
                true,
                false
        );

        assertTrue(RuleEvaluator.matches("author.team.name = 'Math'", context));
        assertFalse(RuleEvaluator.matches("reviewers.name = 'Ada'", context));
        assertTrue(RuleEvaluator.matches("reviewers.name ?= 'Ada'", context));
        assertTrue(RuleEvaluator.matches("@request.body.author.name = 'Ada'", context));
        assertTrue(RuleEvaluator.matches("@request.auth.team.name = 'Math'", context));

        RuleEvaluator.Context authorContext = RecordFieldResolverSupport.context(
                store,
                users,
                ada,
                Map.of(),
                Map.of(),
                "GET",
                null,
                true,
                false
        );
        assertTrue(RuleEvaluator.matches("posts_via_author.title ?= 'Resolver'", authorContext));
        assertFalse(RecordFieldResolverSupport.validPath(store, posts, "author.secret", false));
        assertTrue(RecordFieldResolverSupport.validPath(store, posts, "author.secret", true));
    }

    private static CollectionSchema collection(String id, String name, FieldSchema... fields) {
        CollectionSchema collection = new CollectionSchema();
        collection.id = id;
        collection.name = name;
        collection.type = "base";
        collection.listRule = "";
        collection.viewRule = "";
        collection.fields = List.of(fields);
        return collection;
    }

    private static FieldSchema field(
            String name,
            String type,
            String collectionId,
            int maxSelect,
            boolean hidden
    ) {
        FieldSchema field = new FieldSchema();
        field.name = name;
        field.type = type;
        field.collectionId = collectionId;
        field.maxSelect = maxSelect;
        field.hidden = hidden;
        return field;
    }

    private static final class TestStore implements RecordProcessor.StoreContext {
        private final Map<String, CollectionSchema> collections = new LinkedHashMap<>();
        private final Map<String, List<Map<String, Object>>> records;

        private TestStore(
                List<CollectionSchema> collections,
                Map<String, List<Map<String, Object>>> records
        ) {
            for (CollectionSchema collection : collections) {
                this.collections.put(collection.id, collection);
                this.collections.put(collection.name, collection);
            }
            this.records = records;
        }

        @Override
        public CollectionSchema getCollection(String nameOrId) {
            return collections.get(nameOrId);
        }

        @Override
        public Map<String, Object> getRecord(CollectionSchema collection, String id) {
            return recordsForRule(collection.name).stream()
                    .filter(record -> id.equals(record.get("id")))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public Map<String, Object> findRecordByEmail(CollectionSchema collection, String email) {
            return null;
        }

        @Override
        public void updateRecordField(CollectionSchema collection, String recordId, Map<String, Object> fields) {
        }

        @Override
        public boolean canView(
                CollectionSchema collection,
                Map<String, Object> record,
                Map<String, String> query,
                RequestPrincipal principal
        ) {
            return true;
        }

        @Override
        public List<Map<String, Object>> recordsForRule(String collectionName) {
            CollectionSchema collection = getCollection(collectionName);
            return collection == null ? List.of() : records.getOrDefault(collection.id, List.of());
        }
    }
}
