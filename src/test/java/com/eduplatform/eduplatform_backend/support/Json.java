package com.eduplatform.eduplatform_backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for building request bodies and reading response bodies. */
public final class Json {

    private Json() {}

    /**
     * A JSON object from alternating keys and values. Unlike {@code Map.of} it keeps insertion
     * order and accepts null values, and the result is mutable so a test can adjust one field of
     * a shared template.
     */
    public static Map<String, Object> object(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected alternating keys and values");
        }
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            object.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return object;
    }

    /** The elements of a JSON array of strings; empty for anything that is not an array. */
    public static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(element -> out.add(element.asText()));
        }
        return out;
    }

    /** One field of every element of a page's {@code content} array, in page order. */
    public static List<String> field(JsonNode page, String name) {
        List<String> out = new ArrayList<>();
        page.path("content").forEach(element -> out.add(element.path(name).asText()));
        return out;
    }

    /** The {@code content} element whose {@code field} equals {@code value}, or a missing node. */
    public static JsonNode find(JsonNode page, String field, String value) {
        for (JsonNode element : page.path("content")) {
            if (value.equals(element.path(field).asText())) return element;
        }
        return MissingNode.getInstance();
    }
}
