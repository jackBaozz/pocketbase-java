package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlEndpointTest {
  private RelationalStorageEngine engine;
  private ObjectMapper mapper;

  @BeforeEach
  void setup(@TempDir Path tempDir) {
    mapper = new ObjectMapper();
    engine = RelationalStorageEngine.open(tempDir, null, null);
  }

  @Test
  void testValidSelect() throws Exception {
    Map<String, Object> body = Map.of("query", "SELECT 1 as val");
    Map<String, Object> result = engine.runSql(mapper.valueToTree(body));

    assertNotNull(result);
    assertEquals(0L, result.get("affectedRows"));

    List<Map<String, Object>> columns = Unsafe.stringObjectMapList(result.get("columns"));
    assertEquals(1, columns.size());
    assertEquals("val", columns.get(0).get("name"));

    @SuppressWarnings("unchecked")
    List<List<Object>> rows = (List<List<Object>>) result.get("rows");
    assertEquals(1, rows.size());
    assertEquals(1, rows.get(0).get(0));
  }

  @Test
  void testInvalidSql() throws Exception {
    Map<String, Object> body = Map.of("query", "SELECT * FROM definitely_not_exists");

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> {
              engine.runSql(mapper.valueToTree(body));
            });

    assertEquals(400, e.status());
  }
}
