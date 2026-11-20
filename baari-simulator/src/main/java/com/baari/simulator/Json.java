package com.baari.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader (objects, arrays, strings, numbers, booleans, null) so the simulator needs
 * no library. Objects become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers
 * {@code Double}. Only meant for our own config files.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("unexpected trailing text");
        }
        return v;
    }

    private Object value() {
        ws();
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f', 'n' -> literal();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            m.put(key, value());
            ws();
            if (peek() == ',') {
                i++;
            } else {
                expect('}');
                return m;
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value());
            ws();
            if (peek() == ',') {
                i++;
            } else {
                expect(']');
                return l;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (peek() != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
                char e = s.charAt(i++);
                b.append(
                        switch (e) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'u' -> {
                                char u = (char) Integer.parseInt(s.substring(i, i + 4), 16);
                                i += 4;
                                yield u;
                            }
                            default -> e;
                        });
            } else {
                b.append(c);
            }
        }
        i++;
        return b.toString();
    }

    private Object literal() {
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw error("bad literal");
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw error("unexpected character '" + s.charAt(i) + "'");
        }
        return Double.valueOf(s.substring(start, i));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(i);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON error at " + i + ": " + msg);
    }
}
