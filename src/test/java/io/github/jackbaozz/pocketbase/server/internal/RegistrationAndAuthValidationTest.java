package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationAndAuthValidationTest {

  private ObjectMapper mapper;
  private FieldSchema passwordField;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    passwordField = new FieldSchema("password_field_id", "password", "password", true, false, true);
  }

  @Test
  @DisplayName("密码校验仅执行字段配置")
  void passwordValidationUsesOnlyFieldConfiguration() {
    for (String password :
        List.of("secret456", "Pass!1234", "this-password-is-longer-than-sixteen")) {
      assertTrue(
          errorsFor(passwordField, password).isEmpty(),
          "未配置约束时不应拒绝密码: " + password);
    }

    FieldSchema constrained =
        new FieldSchema("password_field_id", "password", "password", true, false, true);
    constrained.options.put("min", mapper.getNodeFactory().numberNode(10));
    assertTrue(errorsFor(constrained, "secret456").containsKey("password"));
    constrained.options.clear();
    constrained.options.put("pattern", mapper.getNodeFactory().textNode("^[A-Z]+$"));
    assertTrue(errorsFor(constrained, "secret456").containsKey("password"));
  }

  @Test
  @DisplayName("username 文本字段仅遵循 schema 最小长度")
  void usernameValidationUsesOnlySchemaMinimum() {
    FieldSchema username =
        new FieldSchema("username_field_id", "username", "text", true, false, false);
    assertTrue(errorsFor(username, "abc").isEmpty());
    username.options.put("min", mapper.getNodeFactory().numberNode(4));
    assertTrue(errorsFor(username, "abc").containsKey("username"));
  }

  @Test
  @DisplayName("Auth username 需要调用方显式声明唯一索引")
  void authUsernameDoesNotReceiveAnImplicitUniqueIndex() {
    CollectionSchema col = new CollectionSchema();
    col.type = "auth";
    col.name = "users";
    col.fields = java.util.List.of(
        new FieldSchema("id_1", "id", "text", true, false, false),
        new FieldSchema("email_1", "email", "email", true, true, false),
        new FieldSchema("username_1", "username", "text", true, false, false)
    );
    AuthCollectionFields.normalize(col);

    assertNotNull(col.indexes);
    assertFalse(col.indexes.stream().anyMatch(idx -> idx.contains("username")));
    col.passwordAuth.identityFields = List.of("username");
    assertThrows(
        ApiException.class,
        () -> AuthCollectionConfigValidation.validate(col, "Failed to create collection."));
  }

  @Test
  @DisplayName("OAuth 自动密码保持 30 位高熵随机值")
  void oauth2GeneratedPasswordsAreLongAndRandom() {
    String first = IdGenerator.randomPassword();
    String second = IdGenerator.randomPassword();

    assertTrue(first.matches("[a-z0-9]{30}"));
    assertTrue(second.matches("[a-z0-9]{30}"));
    assertNotEquals(first, second);
  }

  private Map<String, Object> errorsFor(FieldSchema field, String value) {
    Map<String, Object> errors = new LinkedHashMap<>();
    FieldValidator.normalizeFieldValue(
        mapper, field, new TextNode(value), false, errors, (collection, id) -> true);
    return errors;
  }
}
