package com.geyserextra.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for JSON operations using Gson.
 *
 * This class provides static utility methods for serializing and deserializing
 * objects to/from JSON format. It uses a shared Gson instance configured with
 * sensible defaults for the GeyserExtra project.
 *
 * Thread-safe: The Gson instances used are thread-safe for serialization
 * and deserialization operations.
 */
public final class JsonUtil {

    private static final Logger LOGGER = Logger.getLogger(JsonUtil.class.getName());

    /**
     * Standard Gson instance for compact JSON output.
     */
    private static final Gson GSON = createGson(false);

    /**
     * Pretty-printing Gson instance for human-readable JSON output.
     */
    private static final Gson GSON_PRETTY = createGson(true);

    /**
     * Private constructor to prevent instantiation.
     */
    private JsonUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Creates a configured Gson instance.
     *
     * @param prettyPrint Whether to enable pretty printing
     * @return A configured Gson instance
     */
    private static Gson createGson(boolean prettyPrint) {
        GsonBuilder builder = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls();

        if (prettyPrint) {
            builder.setPrettyPrinting();
        }

        return builder.create();
    }

    /**
     * Gets the standard Gson instance.
     *
     * @return The shared Gson instance
     */
    public static Gson getGson() {
        return GSON;
    }

    /**
     * Gets the pretty-printing Gson instance.
     *
     * @return The shared pretty-printing Gson instance
     */
    public static Gson getPrettyGson() {
        return GSON_PRETTY;
    }

    /**
     * Serializes an object to a JSON string.
     *
     * @param object The object to serialize
     * @return The JSON string representation
     */
    public static String toJson(Object object) {
        return GSON.toJson(object);
    }

    /**
     * Serializes an object to a pretty-printed JSON string.
     *
     * @param object The object to serialize
     * @return The pretty-printed JSON string representation
     */
    public static String toPrettyJson(Object object) {
        return GSON_PRETTY.toJson(object);
    }

    /**
     * Serializes an object to a JSON string with a specific type.
     *
     * @param object The object to serialize
     * @param type   The type of the object
     * @return The JSON string representation
     */
    public static String toJson(Object object, Type type) {
        return GSON.toJson(object, type);
    }

    /**
     * Deserializes a JSON string to an object.
     *
     * @param json  The JSON string
     * @param clazz The class of the target type
     * @param <T>   The target type
     * @return The deserialized object, or null if json is null
     * @throws JsonSyntaxException if the JSON is malformed
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return GSON.fromJson(json, clazz);
    }

    /**
     * Deserializes a JSON string to an object with a specific type.
     *
     * @param json The JSON string
     * @param type The target type
     * @param <T>  The target type
     * @return The deserialized object, or null if json is null
     * @throws JsonSyntaxException if the JSON is malformed
     */
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return GSON.fromJson(json, type);
    }

    /**
     * Deserializes JSON from a Reader.
     *
     * @param reader The reader to read JSON from
     * @param clazz  The class of the target type
     * @param <T>    The target type
     * @return The deserialized object
     * @throws JsonSyntaxException if the JSON is malformed
     * @throws NullPointerException if reader or clazz is null
     */
    public static <T> T fromJson(Reader reader, Class<T> clazz) {
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        return GSON.fromJson(reader, clazz);
    }

    /**
     * Deserializes JSON from a Reader with a specific type.
     *
     * @param reader The reader to read JSON from
     * @param type   The target type
     * @param <T>    The target type
     * @return The deserialized object
     * @throws JsonSyntaxException if the JSON is malformed
     * @throws NullPointerException if reader or type is null
     */
    public static <T> T fromJson(Reader reader, Type type) {
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(type, "type must not be null");
        return GSON.fromJson(reader, type);
    }

    /**
     * Safely deserializes a JSON string, returning an Optional.
     *
     * @param json  The JSON string
     * @param clazz The class of the target type
     * @param <T>   The target type
     * @return An Optional containing the deserialized object, or empty if parsing fails
     */
    public static <T> Optional<T> tryFromJson(String json, Class<T> clazz) {
        try {
            T result = fromJson(json, clazz);
            return Optional.ofNullable(result);
        } catch (JsonSyntaxException e) {
            LOGGER.log(Level.FINE, "Failed to parse JSON", e);
            return Optional.empty();
        }
    }

    /**
     * Safely deserializes a JSON string with a specific type, returning an Optional.
     *
     * @param json The JSON string
     * @param type The target type
     * @param <T>  The target type
     * @return An Optional containing the deserialized object, or empty if parsing fails
     */
    public static <T> Optional<T> tryFromJson(String json, Type type) {
        try {
            T result = fromJson(json, type);
            return Optional.ofNullable(result);
        } catch (JsonSyntaxException e) {
            LOGGER.log(Level.FINE, "Failed to parse JSON", e);
            return Optional.empty();
        }
    }

    /**
     * Writes an object as JSON to a Writer.
     *
     * @param object The object to serialize
     * @param writer The writer to write to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if writer is null
     */
    public static void toJson(Object object, Writer writer) throws IOException {
        Objects.requireNonNull(writer, "writer must not be null");
        GSON.toJson(object, writer);
    }

    /**
     * Writes an object as pretty-printed JSON to a Writer.
     *
     * @param object The object to serialize
     * @param writer The writer to write to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if writer is null
     */
    public static void toPrettyJson(Object object, Writer writer) throws IOException {
        Objects.requireNonNull(writer, "writer must not be null");
        GSON_PRETTY.toJson(object, writer);
    }

    /**
     * Reads an object from a JSON file.
     *
     * @param path  The path to the JSON file
     * @param clazz The class of the target type
     * @param <T>   The target type
     * @return The deserialized object
     * @throws IOException if an I/O error occurs or the file does not exist
     * @throws JsonSyntaxException if the JSON is malformed
     * @throws NullPointerException if path or clazz is null
     */
    public static <T> T readFromFile(Path path, Class<T> clazz) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");

        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, clazz);
        }
    }

    /**
     * Reads an object from a JSON file with a specific type.
     *
     * @param path The path to the JSON file
     * @param type The target type
     * @param <T>  The target type
     * @return The deserialized object
     * @throws IOException if an I/O error occurs or the file does not exist
     * @throws JsonSyntaxException if the JSON is malformed
     * @throws NullPointerException if path or type is null
     */
    public static <T> T readFromFile(Path path, Type type) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");

        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, type);
        }
    }

    /**
     * Writes an object to a JSON file.
     *
     * @param object The object to serialize
     * @param path   The path to write to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if path is null
     */
    public static void writeToFile(Object object, Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        // Ensure parent directories exist
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(object, writer);
        }
    }

    /**
     * Writes an object to a JSON file with pretty printing.
     *
     * @param object The object to serialize
     * @param path   The path to write to
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if path is null
     */
    public static void writePrettyToFile(Object object, Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        // Ensure parent directories exist
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON_PRETTY.toJson(object, writer);
        }
    }

    /**
     * Parses a JSON string into a JsonElement.
     *
     * @param json The JSON string
     * @return The parsed JsonElement
     * @throws JsonSyntaxException if the JSON is malformed
     */
    public static JsonElement parseJson(String json) {
        return JsonParser.parseString(json);
    }

    /**
     * Checks if a string is valid JSON.
     *
     * @param json The string to check
     * @return true if the string is valid JSON
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonParser.parseString(json);
            return true;
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    /**
     * Converts an object to a JsonElement.
     *
     * @param object The object to convert
     * @return The JsonElement representation
     */
    public static JsonElement toJsonElement(Object object) {
        return GSON.toJsonTree(object);
    }

    /**
     * Converts a JsonElement to an object.
     *
     * @param element The JsonElement to convert
     * @param clazz   The class of the target type
     * @param <T>     The target type
     * @return The deserialized object
     * @throws NullPointerException if element or clazz is null
     */
    public static <T> T fromJsonElement(JsonElement element, Class<T> clazz) {
        Objects.requireNonNull(element, "element must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        return GSON.fromJson(element, clazz);
    }

    /**
     * Converts a JsonElement to an object with a specific type.
     *
     * @param element The JsonElement to convert
     * @param type    The target type
     * @param <T>     The target type
     * @return The deserialized object
     * @throws NullPointerException if element or type is null
     */
    public static <T> T fromJsonElement(JsonElement element, Type type) {
        Objects.requireNonNull(element, "element must not be null");
        Objects.requireNonNull(type, "type must not be null");
        return GSON.fromJson(element, type);
    }
}
