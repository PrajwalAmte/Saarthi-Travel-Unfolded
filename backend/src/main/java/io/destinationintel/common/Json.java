package io.destinationintel.common;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helper around Jackson for reading and writing jsonb columns. */
@Component
public class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public JsonNode tree(String json) {
        return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
    }

    public Map<String, Object> map(String json) {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, MAP);
    }

    public List<Object> list(String json) {
        return json == null || json.isBlank() ? List.of() : mapper.readValue(json, LIST);
    }

    public <T> T read(String json, Class<T> type) {
        return json == null || json.isBlank() ? null : mapper.readValue(json, type);
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
