package org.apvgakasinay.fixtures;

import java.util.List;
import java.util.Map;

/** The three JSON Patch operations used by the negative fixture files, on plain maps and lists. */
final class JsonPatch {

    private JsonPatch() {
    }

    @SuppressWarnings("unchecked")
    static void apply(Object document, Map<String, Object> operation) {
        String op = (String) operation.get("op");
        String[] parts = ((String) operation.get("path")).substring(1).split("/");
        Object target = document;
        for (int i = 0; i < parts.length - 1; i++) {
            target = target instanceof List<?> list ? list.get(Integer.parseInt(parts[i])) : ((Map<String, Object>) target).get(parts[i]);
        }
        String last = parts[parts.length - 1];
        Object value = operation.get("value");
        if (target instanceof List<?>) {
            List<Object> list = (List<Object>) target;
            switch (op) {
                case "add" -> list.add(last.equals("-") ? list.size() : Integer.parseInt(last), value);
                case "replace" -> list.set(Integer.parseInt(last), value);
                case "remove" -> list.remove(Integer.parseInt(last));
                default -> throw new IllegalArgumentException(op);
            }
        } else {
            Map<String, Object> map = (Map<String, Object>) target;
            switch (op) {
                case "add", "replace" -> map.put(last, value);
                case "remove" -> map.remove(last);
                default -> throw new IllegalArgumentException(op);
            }
        }
    }
}
