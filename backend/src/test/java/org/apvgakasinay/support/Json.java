package org.apvgakasinay.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** JSON helpers for tests. Independent of the JSON library the application itself uses. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private Json() {
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException invalid) {
            throw new AssertionError("not JSON: " + text, invalid);
        }
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(Path file) {
        try {
            return MAPPER.readValue(Files.readAllBytes(file), Map.class);
        } catch (IOException unreadable) {
            throw new AssertionError("cannot read " + file, unreadable);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readList(Path file) {
        try {
            return MAPPER.readValue(Files.readAllBytes(file), List.class);
        } catch (IOException unreadable) {
            throw new AssertionError("cannot read " + file, unreadable);
        }
    }

    /** A deep copy made of plain maps and lists. */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        return (T) MAPPER.convertValue(MAPPER.valueToTree(value), Object.class);
    }

    /** Text form with sorted keys and one number format, so 5 (int) equals 5 (long). */
    public static String canonical(Object value) {
        try {
            Object plain = value instanceof JsonNode node ? MAPPER.treeToValue(node, Object.class) : value;
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.convertValue(plain, Object.class));
        } catch (JsonProcessingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
