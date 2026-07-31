package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ViewQuerySupportTest {

  @Test
  void normalizesSingleSelectWithoutRejectingFunctionStars() {
    assertEquals(
        "select count(*) as total, id from posts",
        ViewQuerySupport.normalizeSingleSelect("(select count(*) as total, id from posts); "));
    assertEquals(
        "select /* explicit columns */ id, title from posts",
        ViewQuerySupport.normalizeSingleSelect(
            "select /* explicit columns */ id, title from posts"));
  }

  @Test
  void rejectsMultipleStatementsAndWildcardColumns() {
    assertEquals(
        "multiple statements are not supported",
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewQuerySupport.normalizeSingleSelect(
                "select id from posts; select id from posts"))
            .getMessage());
    assertEquals(
        ViewQuerySupport.WILDCARD_ERROR,
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewQuerySupport.normalizeSingleSelect("select posts.* from posts"))
            .getMessage());
  }

  @Test
  void validatesRequiredUniqueSampleIds() {
    assertEquals(
        "missing required id column (you can use `(ROW_NUMBER() OVER()) as id` if you don't have one)",
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewQuerySupport.result(
                List.of(new ViewQuerySupport.Column("title", "text")),
                List.of(List.of("a"))))
            .getMessage());
    assertEquals(
        "the query could return records with non-unique ids",
        assertThrows(
            IllegalArgumentException.class,
            () -> ViewQuerySupport.result(
                List.of(new ViewQuerySupport.Column("id", "text")),
                List.of(List.of("same"), List.of("same"))))
            .getMessage());
  }
}
