package io.github.jackbaozz.pocketbase.server.internal;

import org.jooq.DataType;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldTypeMappingTest {

    @Test
    void textMapsToVarchar() {
        DataType<?> type = FieldTypeMapping.sqlType("text");
        assertEquals(SQLDataType.VARCHAR(2000), type);
    }

    @Test
    void editorMapsToVarchar() {
        DataType<?> type = FieldTypeMapping.sqlType("editor");
        assertEquals(SQLDataType.VARCHAR(2000), type);
    }

    @Test
    void emailMapsToVarchar255() {
        DataType<?> type = FieldTypeMapping.sqlType("email");
        assertEquals(SQLDataType.VARCHAR(255), type);
    }

    @Test
    void urlMapsToVarchar2048() {
        DataType<?> type = FieldTypeMapping.sqlType("url");
        assertEquals(SQLDataType.VARCHAR(2048), type);
    }

    @Test
    void passwordMapsToVarchar() {
        DataType<?> type = FieldTypeMapping.sqlType("password");
        assertEquals(SQLDataType.VARCHAR(255), type);
    }

    @Test
    void numberMapsToDecimal() {
        DataType<?> type = FieldTypeMapping.sqlType("number");
        assertEquals(SQLDataType.DECIMAL(20, 8), type);
    }

    @Test
    void boolMapsToBoolean() {
        DataType<?> type = FieldTypeMapping.sqlType("bool");
        assertEquals(SQLDataType.BOOLEAN, type);
    }

    @Test
    void booleanAliasMapsToBoolean() {
        DataType<?> type = FieldTypeMapping.sqlType("boolean");
        assertEquals(SQLDataType.BOOLEAN, type);
    }

    @Test
    void dateMapsToVarchar64() {
        DataType<?> type = FieldTypeMapping.sqlType("date");
        assertEquals(SQLDataType.VARCHAR(64), type);
    }

    @Test
    void autodateMapsToVarchar64() {
        DataType<?> type = FieldTypeMapping.sqlType("autodate");
        assertEquals(SQLDataType.VARCHAR(64), type);
    }

    @Test
    void selectMapsToClob() {
        DataType<?> type = FieldTypeMapping.sqlType("select");
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void jsonMapsToClob() {
        DataType<?> type = FieldTypeMapping.sqlType("json");
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void fileMapsToClob() {
        DataType<?> type = FieldTypeMapping.sqlType("file");
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void relationMapsToClob() {
        DataType<?> type = FieldTypeMapping.sqlType("relation");
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void geopointMapsToClob() {
        DataType<?> type = FieldTypeMapping.sqlType("geopoint");
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void unknownTypeFallsBackToClob() {
        DataType<?> type = FieldTypeMapping.sqlType("custom-future-type");
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void nullTypeFallsBackToClob() {
        DataType<?> type = FieldTypeMapping.sqlType(null);
        assertEquals(SQLDataType.CLOB, type);
    }

    @Test
    void typeIsCaseInsensitive() {
        assertEquals(FieldTypeMapping.sqlType("TEXT"), FieldTypeMapping.sqlType("text"));
        assertEquals(FieldTypeMapping.sqlType("Number"), FieldTypeMapping.sqlType("number"));
        assertEquals(FieldTypeMapping.sqlType("BOOL"), FieldTypeMapping.sqlType("bool"));
    }

    @Test
    void typeTrimsWhitespace() {
        assertEquals(FieldTypeMapping.sqlType(" text "), FieldTypeMapping.sqlType("text"));
    }

    @Test
    void isJsonStoredTypeForCompoundTypes() {
        assertTrue(FieldTypeMapping.isJsonStoredType("select"));
        assertTrue(FieldTypeMapping.isJsonStoredType("json"));
        assertTrue(FieldTypeMapping.isJsonStoredType("file"));
        assertTrue(FieldTypeMapping.isJsonStoredType("relation"));
        assertTrue(FieldTypeMapping.isJsonStoredType("geopoint"));
    }

    @Test
    void isNotJsonStoredTypeForScalarTypes() {
        assertFalse(FieldTypeMapping.isJsonStoredType("text"));
        assertFalse(FieldTypeMapping.isJsonStoredType("number"));
        assertFalse(FieldTypeMapping.isJsonStoredType("bool"));
        assertFalse(FieldTypeMapping.isJsonStoredType("date"));
        assertFalse(FieldTypeMapping.isJsonStoredType("email"));
        assertFalse(FieldTypeMapping.isJsonStoredType("url"));
        assertFalse(FieldTypeMapping.isJsonStoredType("password"));
    }

    @Test
    void isJsonStoredTypeHandlesNull() {
        assertFalse(FieldTypeMapping.isJsonStoredType(null));
    }
}
