package assos.hytale.servermanagement.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Small helpers on top of Gson for building API responses and reading request
 * bodies. Gson is provided by the Hytale server at runtime, so it is never
 * bundled with the plugin.
 */
public final class Json {

    private Json() {
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonObject error(String code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        root.add("error", error);
        return root;
    }

    public static JsonObject success() {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        return root;
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    public static String write(JsonElement element) {
        return element.toString();
    }

    /**
     * Parses a request body, returning an empty object for blank input.
     *
     * @param body the raw request body
     * @return the parsed JSON object
     * @throws JsonSyntaxException when the body is not valid JSON
     */
    public static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new JsonSyntaxException("Expected a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    public static String requireString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("Missing required field '" + field + "'");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string");
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' cannot be empty");
        }
        return value;
    }

    public static boolean getBoolean(JsonObject object, String field, boolean fallback) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a boolean");
        }
        return element.getAsBoolean();
    }

    /**
     * Returns an optional string field, trimmed, or {@code null} when absent.
     *
     * @param object the source object
     * @param field  the field name
     * @return the trimmed value or {@code null}
     */
    public static String getString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string");
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Returns an optional integer field, or {@code null} when absent.
     *
     * @param object the source object
     * @param field  the field name
     * @return the integer value or {@code null}
     */
    public static Integer getInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a number");
        }
        return element.getAsInt();
    }
}
