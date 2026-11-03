/*
 * StandaloneRestClient - a small standalone REST client (Postman-like) built only on the JDK.
 *
 *   java StandaloneRestClient.java              start the GUI
 *   java StandaloneRestClient.java --selftest   run the built-in tests
 *
 * Workspace file: data\workspace.json in the app's folder (override with -Drestclient.workspace=<file>).
 */

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.zip.*;
import javax.net.ssl.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import javax.swing.text.JTextComponent;
import javax.swing.tree.*;
import javax.swing.undo.UndoManager;

public class StandaloneRestClient {
    static final String APP = "RestClient";
    static final String VERSION = "1.0";

    public static void main(String[] args) throws Exception {
        // Read once when java.net.http initialises, so it must be set before any HTTP class loads.
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null)
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        if (args.length > 0 && args[0].equals("--selftest")) {
            System.exit(SelfTest.run());
        }
        Path file = Storage.defaultFile();
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            new Ui(file).start();
        });
    }

    // =====================================================================================
    // JSON
    // =====================================================================================

    static final class JsonException extends RuntimeException {
        JsonException(String message) { super(message); }
    }

    static final class Json {
        /** A JSON number kept as its source text, so pretty-printing never rounds or reformats it. */
        record Num(String text) {
            @Override public String toString() { return text; }
        }

        static Object parse(String text) {
            Parser p = new Parser(text.startsWith("\uFEFF") ? text.substring(1) : text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (!p.atEnd()) throw p.error("Unexpected text after the JSON value");
            return v;
        }

        static String write(Object v) {
            StringBuilder b = new StringBuilder();
            write(b, v, false, 0);
            return b.toString();
        }

        static String pretty(Object v) {
            StringBuilder b = new StringBuilder();
            write(b, v, true, 0);
            return b.toString();
        }

        /** A value as an environment-variable string: strings stay raw, everything else becomes compact JSON. */
        static String asText(Object v) {
            return v instanceof String s ? s : write(v);
        }

        /** Looks up a value by a simple path: {@code data.items[0].id}, {@code $.a["b c"]}, {@code [2]}. */
        static Object at(Object root, String path) {
            String p = path.trim();
            if (p.startsWith("$")) p = p.substring(1);
            Object cur = root;
            int i = 0;
            while (i < p.length()) {
                char c = p.charAt(i);
                if (c == '.') { i++; continue; }
                if (c == '[') {
                    int end = p.indexOf(']', i);
                    if (end < 0) throw new JsonException("Unclosed '[' in path " + path);
                    String inside = p.substring(i + 1, end).trim();
                    i = end + 1;
                    if (inside.length() >= 2 && (inside.charAt(0) == '"' || inside.charAt(0) == '\''))
                        cur = field(cur, inside.substring(1, inside.length() - 1), path);
                    else cur = index(cur, inside, path);
                } else {
                    int end = i;
                    while (end < p.length() && p.charAt(end) != '.' && p.charAt(end) != '[') end++;
                    cur = field(cur, p.substring(i, end), path);
                    i = end;
                }
            }
            return cur;
        }

        private static Object field(Object cur, String key, String path) {
            if (cur instanceof Map<?, ?> m && m.containsKey(key)) return m.get(key);
            throw new JsonException("Path " + path + ": no field '" + key + "'");
        }

        private static Object index(Object cur, String idx, String path) {
            int n;
            try { n = Integer.parseInt(idx); } catch (NumberFormatException e) { throw new JsonException("Path " + path + ": bad index [" + idx + "]"); }
            if (cur instanceof List<?> l && n >= 0 && n < l.size()) return l.get(n);
            throw new JsonException("Path " + path + ": no element [" + n + "]");
        }

        // ---- loose accessors used when reading files whose shape we don't control
        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }

        @SuppressWarnings("unchecked")
        static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }

        static String str(Object o) {
            if (o == null) return "";
            if (o instanceof String s) return s;
            if (o instanceof Map || o instanceof List) return write(o);
            return o.toString();
        }

        static boolean bool(Object o, boolean def) {
            if (o instanceof Boolean b) return b;
            if (o instanceof String s && (s.equals("true") || s.equals("false"))) return Boolean.parseBoolean(s);
            return def;
        }

        static int integer(Object o, int def) {
            try { return Integer.parseInt(str(o).trim()); } catch (NumberFormatException e) { return def; }
        }

        private static void write(StringBuilder b, Object v, boolean pretty, int level) {
            if (v == null) b.append("null");
            else if (v instanceof String s) quote(b, s);
            else if (v instanceof Boolean || v instanceof Num || v instanceof Number) b.append(v);
            else if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) { b.append("{}"); return; }
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    newline(b, pretty, level + 1);
                    quote(b, String.valueOf(e.getKey()));
                    b.append(pretty ? ": " : ":");
                    write(b, e.getValue(), pretty, level + 1);
                }
                newline(b, pretty, level);
                b.append('}');
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) { b.append("[]"); return; }
                b.append('[');
                boolean first = true;
                for (Object e : l) {
                    if (!first) b.append(',');
                    first = false;
                    newline(b, pretty, level + 1);
                    write(b, e, pretty, level + 1);
                }
                newline(b, pretty, level);
                b.append(']');
            } else quote(b, v.toString());
        }

        private static void newline(StringBuilder b, boolean pretty, int level) {
            if (!pretty) return;
            b.append('\n');
            for (int i = 0; i < level; i++) b.append("  ");
        }

        private static void quote(StringBuilder b, String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    case '\b' -> b.append("\\b");
                    case '\f' -> b.append("\\f");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }

        private static final class Parser {
            final String s;
            int i;

            Parser(String s) { this.s = s; }

            boolean atEnd() { return i >= s.length(); }

            JsonException error(String message) { return new JsonException(message + " at position " + i); }

            boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

            void expect(char c) {
                if (!peek(c)) throw error("Expected '" + c + "'");
                i++;
            }

            void ws() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                    else break;
                }
            }

            Object value() {
                if (atEnd()) throw error("Unexpected end of JSON");
                char c = s.charAt(i);
                switch (c) {
                    case '{': return object();
                    case '[': return array();
                    case '"': return string();
                    case 't': literal("true"); return Boolean.TRUE;
                    case 'f': literal("false"); return Boolean.FALSE;
                    case 'n': literal("null"); return null;
                    default:
                        if (c == '-' || (c >= '0' && c <= '9')) return number();
                        throw error("Unexpected character '" + c + "'");
                }
            }

            void literal(String word) {
                if (!s.startsWith(word, i)) throw error("Expected '" + word + "'");
                i += word.length();
            }

            Map<String, Object> object() {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    if (!peek('"')) throw error("Expected a string key");
                    String key = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(key, value());
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect('}');
                    return m;
                }
            }

            List<Object> array() {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect(']');
                    return l;
                }
            }

            String string() {
                i++;
                StringBuilder b = new StringBuilder();
                while (true) {
                    if (atEnd()) throw error("Unterminated string");
                    char c = s.charAt(i++);
                    if (c == '"') return b.toString();
                    if (c != '\\') { b.append(c); continue; }
                    if (atEnd()) throw error("Unterminated string");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> b.append(e);
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw error("Bad \\u escape");
                            try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                            catch (NumberFormatException ex) { throw error("Bad \\u escape"); }
                            i += 4;
                        }
                        default -> throw error("Bad escape '\\" + e + "'");
                    }
                }
            }

            Num number() {
                int start = i;
                if (peek('-')) i++;
                digits();
                if (peek('.')) { i++; digits(); }
                if (peek('e') || peek('E')) {
                    i++;
                    if (peek('+') || peek('-')) i++;
                    digits();
                }
                return new Num(s.substring(start, i));
            }

            void digits() {
                int start = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == start) throw error("Expected a digit");
            }
        }
    }

    // =====================================================================================
    // Model
    // =====================================================================================

    static final class KeyValue {
        String key = "";
        String value = "";
        boolean enabled = true;

        KeyValue() { }

        KeyValue(String key, String value, boolean enabled) {
            this.key = key;
            this.value = value;
            this.enabled = enabled;
        }

        KeyValue copy() { return new KeyValue(key, value, enabled); }

        boolean active() { return enabled && !key.isBlank(); }
    }

    enum BodyMode { NONE, TEXT, JSON }

    abstract static class Item {
        String name;

        Item(String name) { this.name = name; }

        @Override public String toString() { return name; }
    }

    static class Folder extends Item {
        final List<Item> items = new ArrayList<>();

        Folder(String name) { super(name); }
    }

    static final class Collection extends Folder {
        final List<KeyValue> variables = new ArrayList<>();

        Collection(String name) { super(name); }
    }

    static final class Request extends Item {
        String method = "GET";
        /** URL without the query string; the query lives in {@link #params} so disabled params survive. */
        String baseUrl = "";
        final List<KeyValue> params = new ArrayList<>();
        final List<KeyValue> headers = new ArrayList<>();
        BodyMode bodyMode = BodyMode.NONE;
        String body = "";
        /** key = variable name, value = JSON path (empty = whole body). */
        final List<KeyValue> postActions = new ArrayList<>();

        Request(String name) { super(name); }

        String url() { return Urls.join(baseUrl, params); }

        /** Takes the URL as typed: enabled params are replaced by the typed query, disabled ones are kept. */
        void setUrl(String text) {
            int q = text.indexOf('?');
            baseUrl = q < 0 ? text : text.substring(0, q);
            List<KeyValue> parsed = Urls.parseQuery(q < 0 ? "" : text.substring(q + 1));
            List<KeyValue> disabled = params.stream().filter(p -> !p.enabled).toList();
            params.clear();
            params.addAll(parsed);
            params.addAll(disabled);
        }
    }

    static final class Environment {
        String name;
        final List<KeyValue> vars = new ArrayList<>();

        Environment(String name) { this.name = name; }

        @Override public String toString() { return name; }
    }

    static final class Settings {
        int timeoutSeconds = 30;
        boolean followRedirects = true;
        boolean http2;
        boolean insecureTls;
    }

    static final class Workspace {
        final List<Collection> collections = new ArrayList<>();
        final List<Environment> environments = new ArrayList<>();
        String activeEnvironment = "";
        final Settings settings = new Settings();
        /** x, y, width, height, maximized(0/1); null until the window has been closed once. */
        int[] window;

        Environment activeEnv() {
            for (Environment e : environments) if (e.name.equals(activeEnvironment)) return e;
            return null;
        }
    }

    static int countRequests(Folder f) {
        int n = 0;
        for (Item it : f.items) n += it instanceof Folder sub ? countRequests(sub) : 1;
        return n;
    }

    // =====================================================================================
    // URLs
    // =====================================================================================

    static final class Urls {
        private static final String SAFE = "-._~:/?@!$&'()*+,;=";
        private static final char[] HEX = "0123456789ABCDEF".toCharArray();

        /** Splits a raw query string without decoding it; values stay exactly as typed. */
        static List<KeyValue> parseQuery(String query) {
            List<KeyValue> out = new ArrayList<>();
            if (query == null || query.isEmpty()) return out;
            for (String part : query.split("&", -1)) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                out.add(eq < 0 ? new KeyValue(part, "", true) : new KeyValue(part.substring(0, eq), part.substring(eq + 1), true));
            }
            return out;
        }

        static String join(String base, List<KeyValue> params) {
            StringBuilder b = new StringBuilder(base);
            boolean first = true;
            for (KeyValue p : params) {
                if (!p.enabled || (p.key.isEmpty() && p.value.isEmpty())) continue;
                b.append(first ? '?' : '&').append(p.key);
                if (!p.value.isEmpty()) b.append('=').append(p.value);
                first = false;
            }
            return b.toString();
        }

        /**
         * Percent-encodes only what is illegal in a URL. Existing %XX escapes are kept, so an
         * already-encoded URL is never double-encoded.
         */
        static String encode(String url) {
            int schemeEnd = url.indexOf("://");
            int authStart = schemeEnd < 0 ? -1 : schemeEnd + 3;
            int authEnd = -1;
            if (authStart >= 0) {
                authEnd = url.length();
                for (int k = authStart; k < url.length(); k++) {
                    char c = url.charAt(k);
                    if (c == '/' || c == '?' || c == '#') { authEnd = k; break; }
                }
            }
            StringBuilder b = new StringBuilder();
            boolean fragment = false;
            for (int i = 0; i < url.length(); ) {
                int cp = url.codePointAt(i);
                boolean inAuthority = authStart >= 0 && i >= authStart && i < authEnd;
                if (cp == '%' && i + 2 < url.length() && isHex(url.charAt(i + 1)) && isHex(url.charAt(i + 2))) b.append('%');
                else if (cp == '#' && !fragment) { fragment = true; b.append('#'); }
                else if (cp < 128 && (Character.isLetterOrDigit(cp) || SAFE.indexOf(cp) >= 0 || (inAuthority && (cp == '[' || cp == ']'))))
                    b.append((char) cp);
                else {
                    for (byte x : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8))
                        b.append('%').append(HEX[(x >> 4) & 15]).append(HEX[x & 15]);
                }
                i += Character.charCount(cp);
            }
            return b.toString();
        }

        private static boolean isHex(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }
    }

    // =====================================================================================
    // Storage (own JSON format)
    // =====================================================================================

    static final class Storage {
        static Path defaultFile() {
            String override = System.getProperty("restclient.workspace");
            if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
            return appDir().resolve("data").resolve("workspace.json");
        }

        /**
         * The folder the app runs from: the exe's folder for a jpackage image, the jar's folder,
         * the source file's folder for "java StandaloneRestClient.java", or the classes directory.
         */
        static Path appDir() {
            String exe = System.getProperty("jpackage.app-path");
            if (exe != null && !exe.isBlank()) return Path.of(exe).toAbsolutePath().getParent();
            try {
                var source = StandaloneRestClient.class.getProtectionDomain().getCodeSource();
                if (source != null) {
                    Path p = Path.of(source.getLocation().toURI()).toAbsolutePath();
                    return Files.isDirectory(p) ? p : p.getParent();
                }
            } catch (Exception ignored) { }
            return Path.of("").toAbsolutePath();
        }

        static Workspace load(Path file) throws IOException {
            if (!Files.exists(file)) return new Workspace();
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return fromJson(Json.obj(Json.parse(text)));
        }

        static void save(Workspace w, Path file) throws IOException {
            Path target = file.toAbsolutePath();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, Json.pretty(toJson(w)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        static Map<String, Object> toJson(Workspace w) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", "restclient-workspace");
            m.put("version", 1);
            m.put("activeEnvironment", w.activeEnvironment);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("timeoutSeconds", w.settings.timeoutSeconds);
            s.put("followRedirects", w.settings.followRedirects);
            s.put("http2", w.settings.http2);
            s.put("insecureTls", w.settings.insecureTls);
            m.put("settings", s);
            if (w.window != null) m.put("window", Arrays.stream(w.window).boxed().toList());
            m.put("collections", w.collections.stream().map(Storage::item).toList());
            m.put("environments", w.environments.stream().map(Storage::env).toList());
            return m;
        }

        static Workspace fromJson(Map<String, Object> m) {
            Workspace w = new Workspace();
            w.activeEnvironment = Json.str(m.get("activeEnvironment"));
            Map<String, Object> s = Json.obj(m.get("settings"));
            w.settings.timeoutSeconds = Json.integer(s.get("timeoutSeconds"), 30);
            w.settings.followRedirects = Json.bool(s.get("followRedirects"), true);
            w.settings.http2 = Json.bool(s.get("http2"), false);
            w.settings.insecureTls = Json.bool(s.get("insecureTls"), false);
            List<Object> win = Json.arr(m.get("window"));
            if (win.size() >= 4) w.window = win.stream().mapToInt(o -> Json.integer(o, 0)).toArray();
            for (Object o : Json.arr(m.get("collections"))) {
                Item it = item(Json.obj(o));
                if (it instanceof Collection c) w.collections.add(c);
            }
            for (Object o : Json.arr(m.get("environments"))) w.environments.add(env(Json.obj(o)));
            return w;
        }

        static Map<String, Object> item(Item it) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (it instanceof Request r) {
                m.put("type", "request");
                m.put("name", r.name);
                m.put("method", r.method);
                m.put("url", r.baseUrl);
                m.put("params", kvs(r.params));
                m.put("headers", kvs(r.headers));
                m.put("bodyMode", r.bodyMode.name().toLowerCase(Locale.ROOT));
                m.put("body", r.body);
                m.put("postActions", kvs(r.postActions));
            } else if (it instanceof Folder f) {
                m.put("type", f instanceof Collection ? "collection" : "folder");
                m.put("name", f.name);
                if (f instanceof Collection c) m.put("variables", kvs(c.variables));
                m.put("items", f.items.stream().map(Storage::item).toList());
            }
            return m;
        }

        static Item item(Map<String, Object> m) {
            String type = Json.str(m.get("type"));
            String name = Json.str(m.get("name"));
            if (type.equals("request")) {
                Request r = new Request(name);
                r.method = Json.str(m.get("method")).isEmpty() ? "GET" : Json.str(m.get("method"));
                r.baseUrl = Json.str(m.get("url"));
                readKvs(m.get("params"), r.params);
                readKvs(m.get("headers"), r.headers);
                try { r.bodyMode = BodyMode.valueOf(Json.str(m.get("bodyMode")).toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { r.bodyMode = BodyMode.NONE; }
                r.body = Json.str(m.get("body"));
                readKvs(m.get("postActions"), r.postActions);
                return r;
            }
            Folder f = type.equals("collection") ? new Collection(name) : new Folder(name);
            if (f instanceof Collection c) readKvs(m.get("variables"), c.variables);
            for (Object o : Json.arr(m.get("items"))) f.items.add(item(Json.obj(o)));
            return f;
        }

        static Map<String, Object> env(Environment e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name);
            m.put("variables", kvs(e.vars));
            return m;
        }

        static Environment env(Map<String, Object> m) {
            Environment e = new Environment(Json.str(m.get("name")));
            readKvs(m.get("variables"), e.vars);
            return e;
        }

        static List<Object> kvs(List<KeyValue> list) {
            List<Object> out = new ArrayList<>();
            for (KeyValue kv : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", kv.key);
                m.put("value", kv.value);
                m.put("enabled", kv.enabled);
                out.add(m);
            }
            return out;
        }

        static void readKvs(Object json, List<KeyValue> into) {
            for (Object o : Json.arr(json)) {
                Map<String, Object> m = Json.obj(o);
                into.add(new KeyValue(Json.str(m.get("key")), Json.str(m.get("value")), Json.bool(m.get("enabled"), true)));
            }
        }

        @SuppressWarnings("unchecked")
        static <T extends Item> T copy(T it) { return (T) item(item(it)); }
    }

    // =====================================================================================
    // Postman import (collection v2.0 / v2.1, environment exports)
    // =====================================================================================

    static final class PostmanImporter {
        static final class ImportResult {
            Collection collection;
            Environment environment;
            /** What was not imported, with how many times it occurred. */
            final Map<String, Integer> skipped = new LinkedHashMap<>();

            void skip(String what) { skipped.merge(what, 1, Integer::sum); }
        }

        private static final Pattern ALIAS = Pattern.compile(
                "^(?:var|let|const)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:pm\\.response\\.json\\(\\)|JSON\\.parse\\(\\s*(?:responseBody|pm\\.response\\.text\\(\\))\\s*\\))\\s*;?$");
        private static final Pattern SET = Pattern.compile(
                "^(?:pm\\.(?:environment|collectionVariables|globals|variables)\\.set|postman\\.set(?:Environment|Global)Variable)"
                        + "\\(\\s*([\"'`])(.+?)\\1\\s*,\\s*(.+?)\\s*\\)\\s*;?$");
        private static final Pattern STRINGIFY = Pattern.compile("^JSON\\.stringify\\((.+)\\)$");
        private static final Pattern CHAIN = Pattern.compile(
                "^(pm\\.response\\.json\\(\\)|JSON\\.parse\\(\\s*(?:responseBody|pm\\.response\\.text\\(\\))\\s*\\)|[A-Za-z_$][\\w$]*)"
                        + "((?:\\.[A-Za-z_$][\\w$]*|\\[\\s*\\d+\\s*\\]|\\[\\s*\"[^\"]*\"\\s*\\]|\\[\\s*'[^']*'\\s*\\])*)$");

        static ImportResult importText(String text) {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("Not a Postman collection or environment file");
            Map<String, Object> root = Json.obj(parsed);
            if (root.get("collection") instanceof Map) root = Json.obj(root.get("collection")); // Postman API wrapper
            ImportResult r = new ImportResult();
            if (root.get("info") instanceof Map && root.containsKey("item")) {
                r.collection = collection(root, r);
            } else if (root.get("values") instanceof List) {
                r.environment = environment(root);
            } else if (root.containsKey("requests") || root.containsKey("order")) {
                throw new IllegalArgumentException("Postman v1 collections are not supported. Re-export it from Postman as Collection v2.1.");
            } else {
                throw new IllegalArgumentException("Not a Postman collection or environment file");
            }
            return r;
        }

        private static Collection collection(Map<String, Object> root, ImportResult r) {
            Map<String, Object> info = Json.obj(root.get("info"));
            String schema = Json.str(info.get("schema"));
            if (!schema.isEmpty() && !schema.contains("v2."))
                throw new IllegalArgumentException("Unsupported collection schema: " + schema);
            Collection c = new Collection(orDefault(Json.str(info.get("name")), "Imported collection"));
            for (Object v : Json.arr(root.get("variable"))) {
                Map<String, Object> m = Json.obj(v);
                if (Json.str(m.get("key")).isEmpty()) continue;
                c.variables.add(new KeyValue(Json.str(m.get("key")), Json.str(m.get("value")), !Json.bool(m.get("disabled"), false)));
            }
            skipScripts(root, r);
            Map<String, Object> auth = effectiveAuth(root.get("auth"), null);
            for (Object it : Json.arr(root.get("item"))) c.items.add(item(Json.obj(it), auth, r));
            return c;
        }

        private static Environment environment(Map<String, Object> root) {
            Environment e = new Environment(orDefault(Json.str(root.get("name")), "Imported environment"));
            for (Object v : Json.arr(root.get("values"))) {
                Map<String, Object> m = Json.obj(v);
                if (Json.str(m.get("key")).isEmpty()) continue;
                e.vars.add(new KeyValue(Json.str(m.get("key")), Json.str(m.get("value")), Json.bool(m.get("enabled"), true)));
            }
            return e;
        }

        private static Item item(Map<String, Object> it, Map<String, Object> inheritedAuth, ImportResult r) {
            String name = orDefault(Json.str(it.get("name")), "Untitled");
            if (it.containsKey("item")) {
                Folder f = new Folder(name);
                skipScripts(it, r);
                Map<String, Object> auth = effectiveAuth(it.get("auth"), inheritedAuth);
                for (Object child : Json.arr(it.get("item"))) f.items.add(item(Json.obj(child), auth, r));
                return f;
            }
            Request q = new Request(name);
            Object reqObj = it.get("request");
            if (reqObj instanceof String s) {
                q.setUrl(s);
                applyAuth(q, inheritedAuth, r);
            } else {
                Map<String, Object> rq = Json.obj(reqObj);
                q.method = orDefault(Json.str(rq.get("method")), "GET").toUpperCase(Locale.ROOT);
                url(q, rq.get("url"), r);
                headers(q, rq.get("header"));
                body(q, Json.obj(rq.get("body")), r);
                applyAuth(q, effectiveAuth(rq.get("auth"), inheritedAuth), r);
            }
            events(it, q, r);
            return q;
        }

        private static void url(Request q, Object u, ImportResult r) {
            if (u instanceof String s) { q.setUrl(s); return; }
            Map<String, Object> m = Json.obj(u);
            String raw = Json.str(m.get("raw"));
            if (raw.isEmpty()) raw = urlFromParts(m);
            int qi = raw.indexOf('?');
            q.baseUrl = qi < 0 ? raw : raw.substring(0, qi);
            if (m.get("query") instanceof List<?> query) {
                for (Object o : query) {
                    Map<String, Object> p = Json.obj(o);
                    q.params.add(new KeyValue(Json.str(p.get("key")), Json.str(p.get("value")), !Json.bool(p.get("disabled"), false)));
                }
            } else {
                q.params.addAll(Urls.parseQuery(qi < 0 ? "" : raw.substring(qi + 1)));
            }
            if (!Json.arr(m.get("variable")).isEmpty()) r.skip("Path variables (:name) - sent literally");
        }

        private static String urlFromParts(Map<String, Object> m) {
            StringBuilder b = new StringBuilder();
            String protocol = Json.str(m.get("protocol"));
            if (!protocol.isEmpty()) b.append(protocol).append("://");
            Object host = m.get("host");
            b.append(host instanceof List<?> l ? l.stream().map(Json::str).collect(Collectors.joining(".")) : Json.str(host));
            String port = Json.str(m.get("port"));
            if (!port.isEmpty()) b.append(':').append(port);
            Object path = m.get("path");
            if (path instanceof List<?> l) for (Object seg : l) b.append('/').append(Json.str(seg));
            else if (!Json.str(path).isEmpty()) b.append(Json.str(path).startsWith("/") ? "" : "/").append(Json.str(path));
            return b.toString();
        }

        private static void headers(Request q, Object h) {
            if (h instanceof String s) { // v2.0 allowed "Key: Value" lines
                for (String line : s.split("\\r?\\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) q.headers.add(new KeyValue(line.substring(0, colon).trim(), line.substring(colon + 1).trim(), true));
                }
                return;
            }
            for (Object o : Json.arr(h)) {
                Map<String, Object> m = Json.obj(o);
                if (Json.str(m.get("key")).isEmpty()) continue;
                q.headers.add(new KeyValue(Json.str(m.get("key")), Json.str(m.get("value")), !Json.bool(m.get("disabled"), false)));
            }
        }

        private static void body(Request q, Map<String, Object> b, ImportResult r) {
            if (b.isEmpty() || Json.bool(b.get("disabled"), false)) return;
            String mode = Json.str(b.get("mode"));
            if (mode.equals("raw")) {
                String text = Json.str(b.get("raw"));
                if (text.isEmpty()) return;
                String lang = Json.str(Json.obj(Json.obj(b.get("options")).get("raw")).get("language"));
                String t = text.trim();
                boolean json = lang.equals("json") || (lang.isEmpty() && (t.startsWith("{") || t.startsWith("[")));
                q.body = text;
                q.bodyMode = json ? BodyMode.JSON : BodyMode.TEXT;
            } else if (!mode.isEmpty()) {
                r.skip("Body mode '" + mode + "'");
            }
        }

        private static Map<String, Object> effectiveAuth(Object own, Map<String, Object> inherited) {
            Map<String, Object> a = Json.obj(own);
            String type = Json.str(a.get("type"));
            if (a.isEmpty() || type.isEmpty() || type.equals("inherit")) return inherited;
            return a;
        }

        private static void applyAuth(Request q, Map<String, Object> auth, ImportResult r) {
            if (auth == null) return;
            String type = Json.str(auth.get("type"));
            switch (type) {
                case "", "noauth" -> { }
                case "bearer" -> addHeader(q, "Authorization", "Bearer " + authParam(auth, "bearer", "token"));
                case "basic" -> {
                    String user = authParam(auth, "basic", "username"), pass = authParam(auth, "basic", "password");
                    if (user.contains("{{") || pass.contains("{{")) r.skip("Basic auth that uses variables");
                    else addHeader(q, "Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
                }
                case "apikey" -> {
                    String key = authParam(auth, "apikey", "key"), value = authParam(auth, "apikey", "value");
                    if (key.isEmpty()) key = "x-api-key";
                    if (authParam(auth, "apikey", "in").equals("query")) q.params.add(new KeyValue(key, value, true));
                    else addHeader(q, key, value);
                }
                default -> r.skip("Auth type '" + type + "'");
            }
        }

        private static String authParam(Map<String, Object> auth, String type, String name) {
            Object p = auth.get(type);
            if (p instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, Object> m = Json.obj(o);
                    if (name.equals(Json.str(m.get("key")))) return Json.str(m.get("value"));
                }
                return "";
            }
            return Json.str(Json.obj(p).get(name));
        }

        private static void addHeader(Request q, String name, String value) {
            for (KeyValue h : q.headers) if (h.key.equalsIgnoreCase(name)) return;
            q.headers.add(new KeyValue(name, value, true));
        }

        private static void skipScripts(Map<String, Object> container, ImportResult r) {
            for (Object e : Json.arr(container.get("event")))
                if (!scriptLines(Json.obj(e)).isEmpty()) r.skip("Scripts on collections or folders");
        }

        private static void events(Map<String, Object> it, Request q, ImportResult r) {
            for (Object e : Json.arr(it.get("event"))) {
                Map<String, Object> em = Json.obj(e);
                List<String> lines = scriptLines(em);
                if (lines.isEmpty()) continue;
                if (!Json.str(em.get("listen")).equals("test")) { r.skip("Pre-request scripts"); continue; }
                if (convertTestScript(lines, q)) r.skip("Test script code other than pm.environment.set(...) (not run)");
            }
        }

        /** Non-blank, non-comment script lines. */
        private static List<String> scriptLines(Map<String, Object> event) {
            Object exec = Json.obj(event.get("script")).get("exec");
            List<String> raw = exec instanceof List<?> l ? l.stream().map(Json::str).toList() : List.of(Json.str(exec).split("\\r?\\n"));
            List<String> out = new ArrayList<>();
            for (String line : raw) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("//")) out.add(t);
            }
            return out;
        }

        /** Converts recognisable "set variable from response" lines into post-actions; returns true if other code remains. */
        static boolean convertTestScript(List<String> lines, Request q) {
            Set<String> aliases = new HashSet<>();
            boolean other = false;
            for (String line : lines) {
                Matcher a = ALIAS.matcher(line);
                if (a.matches()) { aliases.add(a.group(1)); continue; }
                Matcher s = SET.matcher(line);
                if (s.matches()) {
                    String path = exprToPath(s.group(3).trim(), aliases);
                    if (path != null) { q.postActions.add(new KeyValue(s.group(2), path, true)); continue; }
                }
                other = true;
            }
            return other;
        }

        static String exprToPath(String expr, Set<String> aliases) {
            if (expr.equals("pm.response.text()") || expr.equals("responseBody")) return "";
            Matcher st = STRINGIFY.matcher(expr);
            if (st.matches()) expr = st.group(1).trim();
            Matcher m = CHAIN.matcher(expr);
            if (!m.matches()) return null;
            String head = m.group(1);
            if (!head.startsWith("pm.response") && !head.startsWith("JSON.parse") && !aliases.contains(head)) return null;
            String chain = m.group(2);
            if (chain.startsWith(".")) chain = chain.substring(1);
            return chain.replace('\'', '"');
        }

        private static String orDefault(String s, String def) { return s.isBlank() ? def : s; }
    }

    // =====================================================================================
    // HTTP engine
    // =====================================================================================

    static final class Response {
        int status;
        String version = "";
        long millis;
        String url = "";
        final List<String[]> headers = new ArrayList<>();
        byte[] bytes = new byte[0];
        /** Decoded body, or null when the body is binary. */
        String text;
        String contentType = "";
        /** Set when no response was received. */
        String error;
        String requestSummary = "";
        final List<String> messages = new ArrayList<>();
        /** Pretty-printed body when it is valid JSON; computed off the UI thread. */
        String pretty;
        private boolean parsed;
        private Object json;
        private String jsonError;

        static Response failed(String summary, String error, List<String> messages) {
            Response r = new Response();
            r.requestSummary = summary;
            r.error = error;
            r.messages.addAll(messages);
            return r;
        }

        Object json() {
            if (!parsed) {
                parsed = true;
                if (text == null) jsonError = "Response body is not text";
                else {
                    try { json = Json.parse(text); }
                    catch (JsonException e) { jsonError = "Response body is not valid JSON (" + e.getMessage() + ")"; }
                }
            }
            if (jsonError != null) throw new JsonException(jsonError);
            return json;
        }

        void analyze() {
            if (text == null || text.length() > 20_000_000) return;
            String t = text.stripLeading();
            if (!contentType.toLowerCase(Locale.ROOT).contains("json") && !t.startsWith("{") && !t.startsWith("[")) return;
            try { pretty = Json.pretty(json()); } catch (JsonException ignored) { }
        }
    }

    static final class Engine {
        static final Pattern VAR = Pattern.compile("\\{\\{([^{}]+)\\}\\}");
        static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
        /** Headers java.net.http refuses to set (Host is allowed via the system property set in main). */
        static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "upgrade");
        private static HttpClient client;
        private static String clientKey;

        static final class Prepared {
            String method;
            URI uri;
            final List<String[]> headers = new ArrayList<>();
            String body;
            final List<String> messages = new ArrayList<>();

            String summary() {
                StringBuilder b = new StringBuilder(method).append(' ').append(uri);
                for (String[] h : headers) b.append("\n  ").append(h[0]).append(": ").append(h[1]);
                if (body != null) b.append("\n  [body: ").append(body.length()).append(" characters]");
                return b.toString();
            }
        }

        /** Collection variables first, then the environment, so environment values win. */
        static Map<String, String> variables(Collection c, Environment env) {
            Map<String, String> v = new HashMap<>();
            if (c != null) for (KeyValue kv : c.variables) if (kv.active()) v.put(kv.key.trim(), kv.value);
            if (env != null) for (KeyValue kv : env.vars) if (kv.active()) v.put(kv.key.trim(), kv.value);
            return v;
        }

        static String resolve(String s, Map<String, String> vars, Set<String> unresolved) {
            String cur = s;
            for (int depth = 0; depth < 10 && cur.contains("{{"); depth++) { // values may reference other variables
                Matcher m = VAR.matcher(cur);
                StringBuilder b = new StringBuilder();
                boolean changed = false;
                while (m.find()) {
                    String name = m.group(1).trim();
                    String val = vars.containsKey(name) ? vars.get(name) : dynamic(name);
                    if (val != null) changed = true;
                    m.appendReplacement(b, Matcher.quoteReplacement(val != null ? val : m.group()));
                }
                m.appendTail(b);
                cur = b.toString();
                if (!changed) break;
            }
            Matcher m = VAR.matcher(cur);
            while (m.find()) unresolved.add(m.group(1).trim());
            return cur;
        }

        private static String dynamic(String name) {
            return switch (name) {
                case "$guid", "$randomUUID" -> UUID.randomUUID().toString();
                case "$timestamp" -> Long.toString(Instant.now().getEpochSecond());
                case "$isoTimestamp" -> Instant.now().toString();
                case "$randomInt" -> Integer.toString(ThreadLocalRandom.current().nextInt(0, 1001));
                default -> null;
            };
        }

        static Prepared prepare(Request r, Map<String, String> vars) {
            Prepared p = new Prepared();
            Set<String> unresolved = new TreeSet<>();
            p.method = r.method.trim().isEmpty() ? "GET" : r.method.trim().toUpperCase(Locale.ROOT);
            String url = resolve(r.url(), vars, unresolved).trim();
            String hint = unresolved.isEmpty() ? "" : " (unresolved variables: " + String.join(", ", unresolved) + ")";
            if (url.isEmpty()) throw new IllegalArgumentException("The URL is empty" + hint);
            if (!SCHEME.matcher(url).find()) url = "http://" + url;
            try { p.uri = new URI(Urls.encode(url)); }
            catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid URL: " + e.getMessage() + hint); }
            String scheme = p.uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https"))
                throw new IllegalArgumentException("Unsupported URL scheme '" + scheme + "'" + hint);
            if (p.uri.getHost() == null) throw new IllegalArgumentException("The URL has no valid host: " + url + hint);
            for (KeyValue h : r.headers)
                if (h.active()) p.headers.add(new String[] {resolve(h.key, vars, unresolved).trim(), resolve(h.value, vars, unresolved)});
            if (r.bodyMode != BodyMode.NONE) {
                p.body = resolve(r.body, vars, unresolved);
                if (!hasHeader(p.headers, "content-type"))
                    p.headers.add(new String[] {"Content-Type", r.bodyMode == BodyMode.JSON ? "application/json" : "text/plain; charset=UTF-8"});
            }
            if (!hasHeader(p.headers, "user-agent")) p.headers.add(new String[] {"User-Agent", APP + "/" + VERSION});
            if (!hasHeader(p.headers, "accept")) p.headers.add(new String[] {"Accept", "*/*"});
            if (!unresolved.isEmpty()) p.messages.add("Unresolved variables (sent as written): " + String.join(", ", unresolved));
            return p;
        }

        private static boolean hasHeader(List<String[]> headers, String name) {
            for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return true;
            return false;
        }

        /** Sends asynchronously; cancelling the returned future aborts the exchange. */
        static CompletableFuture<Response> send(Prepared p, Settings s) {
            HttpRequest req;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(p.uri).timeout(Duration.ofSeconds(Math.max(1, s.timeoutSeconds)));
                for (String[] h : p.headers) {
                    if (RESTRICTED.contains(h[0].toLowerCase(Locale.ROOT))) {
                        p.messages.add("Header '" + h[0] + "' is managed by the HTTP client and was not sent");
                        continue;
                    }
                    try { b.header(h[0], h[1]); }
                    catch (IllegalArgumentException e) { p.messages.add("Header '" + h[0] + "' skipped: " + e.getMessage()); }
                }
                b.method(p.method, p.body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(p.body, StandardCharsets.UTF_8));
                req = b.build();
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(e);
            }
            if (s.insecureTls && "https".equalsIgnoreCase(p.uri.getScheme()))
                p.messages.add("SSL certificate and hostname verification is disabled (like curl -k)");
            long start = System.nanoTime();
            CompletableFuture<HttpResponse<byte[]>> exchange = client(s).sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
            CompletableFuture<Response> result = exchange.thenApply(resp -> toResponse(resp, (System.nanoTime() - start) / 1_000_000, p));
            result.whenComplete((r, e) -> { if (result.isCancelled()) exchange.cancel(true); });
            return result;
        }

        private static Response toResponse(HttpResponse<byte[]> resp, long millis, Prepared p) {
            Response r = new Response();
            r.status = resp.statusCode();
            r.version = resp.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
            r.millis = millis;
            r.url = resp.uri().toString();
            r.requestSummary = p.summary();
            r.messages.addAll(p.messages);
            resp.headers().map().forEach((k, vs) -> {
                if (!k.startsWith(":")) for (String v : vs) r.headers.add(new String[] {k, v});
            });
            r.contentType = resp.headers().firstValue("content-type").orElse("");
            byte[] b = resp.body() == null ? new byte[0] : resp.body();
            String enc = resp.headers().firstValue("content-encoding").orElse("").trim().toLowerCase(Locale.ROOT);
            try {
                if (enc.equals("gzip") || enc.equals("x-gzip")) b = new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes();
                else if (enc.equals("deflate")) b = new InflaterInputStream(new ByteArrayInputStream(b)).readAllBytes();
            } catch (IOException e) {
                r.messages.add("Could not decode the " + enc + " body: " + e.getMessage());
            }
            r.bytes = b;
            if (isText(r.contentType, b)) r.text = new String(b, charset(r.contentType));
            r.analyze();
            return r;
        }

        static boolean isText(String contentType, byte[] body) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")
                    || ct.contains("x-www-form-urlencoded") || ct.contains("yaml") || ct.contains("csv")) return true;
            if (ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/") || ct.startsWith("font/")
                    || ct.contains("octet-stream") || ct.contains("pdf") || ct.contains("zip") || ct.contains("protobuf")) return false;
            for (int i = 0; i < Math.min(body.length, 4096); i++) if (body[i] == 0) return false;
            return true;
        }

        static Charset charset(String contentType) {
            Matcher m = Pattern.compile("(?i)charset=\"?([^;\"\\s]+)").matcher(contentType);
            if (m.find()) {
                try { return Charset.forName(m.group(1)); } catch (Exception ignored) { }
            }
            return StandardCharsets.UTF_8;
        }

        private static synchronized HttpClient client(Settings s) {
            String key = s.timeoutSeconds + "|" + s.followRedirects + "|" + s.http2 + "|" + s.insecureTls;
            if (client != null && key.equals(clientKey)) return client;
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, s.timeoutSeconds)))
                    .followRedirects(s.followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                    .version(s.http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
            if (s.insecureTls) b.sslContext(trustAll());
            if (client != null) client.shutdown(); // lets in-flight requests finish
            client = b.build();
            clientKey = key;
            return client;
        }

        /**
         * Accepts any certificate. Being an X509ExtendedTrustManager also skips the hostname check,
         * which the JDK performs inside the trust manager.
         */
        private static SSLContext trustAll() {
            TrustManager tm = new X509ExtendedTrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { }
                @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
                return ctx;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Runs the request's post-actions on a 2xx response; returns log lines. Must run on the thread that owns the model. */
        static List<String> applyPostActions(Request r, Response res, Collection c, Environment env) {
            List<String> out = new ArrayList<>();
            List<KeyValue> rules = r.postActions.stream().filter(KeyValue::active).toList();
            if (rules.isEmpty()) return out;
            if (res.status < 200 || res.status > 299) {
                out.add("Post-processing skipped: status " + res.status + " is not 2xx");
                return out;
            }
            List<KeyValue> target = env != null ? env.vars : c.variables;
            String scope = env != null ? "environment '" + env.name + "'" : "collection variables";
            for (KeyValue rule : rules) {
                String name = rule.key.trim(), path = rule.value.trim();
                try {
                    String value = path.isEmpty() ? (res.text != null ? res.text : "") : Json.asText(Json.at(res.json(), path));
                    setVar(target, name, value);
                    out.add("Set " + name + " in " + scope + " = " + abbreviate(value, 100));
                } catch (JsonException e) {
                    out.add("Post-processing '" + name + "' failed: " + e.getMessage());
                }
            }
            return out;
        }

        static void setVar(List<KeyValue> vars, String name, String value) {
            for (KeyValue kv : vars) {
                if (kv.key.trim().equals(name)) {
                    kv.value = value;
                    kv.enabled = true;
                    return;
                }
            }
            vars.add(new KeyValue(name, value, true));
        }

        /** True when the failure came from TLS: untrusted/expired certificate, hostname mismatch, handshake error. */
        static boolean isTlsError(Throwable ex) {
            for (Throwable c = ex; c != null; c = c.getCause() == c ? null : c.getCause())
                if (c instanceof SSLException || c instanceof java.security.cert.CertificateException) return true;
            return false;
        }

        static String describe(Throwable ex) {
            Throwable t = ex;
            while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
            if (t instanceof CancellationException) return "Request cancelled";
            if (t instanceof HttpConnectTimeoutException) return "Connection timed out";
            if (t instanceof HttpTimeoutException) return "Request timed out (no response within the configured timeout)";
            StringBuilder b = new StringBuilder();
            Set<String> seen = new HashSet<>();
            for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
                String part = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
                if (seen.add(part)) b.append(b.length() == 0 ? "" : "\n  caused by ").append(part);
            }
            return b.toString();
        }
    }

    static String abbreviate(String s, int max) {
        String one = s.replace("\r", "").replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    static String reasonPhrase(int status) {
        return switch (status) {
            case 100 -> "Continue"; case 101 -> "Switching Protocols";
            case 200 -> "OK"; case 201 -> "Created"; case 202 -> "Accepted"; case 204 -> "No Content"; case 206 -> "Partial Content";
            case 301 -> "Moved Permanently"; case 302 -> "Found"; case 303 -> "See Other"; case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect"; case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request"; case 401 -> "Unauthorized"; case 403 -> "Forbidden"; case 404 -> "Not Found";
            case 405 -> "Method Not Allowed"; case 406 -> "Not Acceptable"; case 408 -> "Request Timeout"; case 409 -> "Conflict";
            case 410 -> "Gone"; case 413 -> "Content Too Large"; case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Content"; case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error"; case 501 -> "Not Implemented"; case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable"; case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =====================================================================================
    // Swing UI
    // =====================================================================================

    /** Editable key/value/enabled table. The last row is a blank "new row": typing into it adds an entry. */
    static final class KvTable extends JPanel {
        final KvModel model;
        final JTable table;

        KvTable(String keyTitle, String valueTitle, Runnable onChange) {
            super(new BorderLayout());
            model = new KvModel(keyTitle, valueTitle, onChange);
            table = new JTable(model);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            table.setSurrendersFocusOnKeystroke(true);
            table.setFillsViewportHeight(true);
            table.setRowHeight(Math.max(table.getRowHeight(), 22));
            TableColumn check = table.getColumnModel().getColumn(0);
            check.setMinWidth(32);
            check.setMaxWidth(32);
            table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "rc-remove");
            table.getActionMap().put("rc-remove", Ui.action(this::removeSelected));
            JButton remove = new JButton("Remove selected");
            remove.addActionListener(e -> removeSelected());
            JLabel hint = new JLabel("Type in the last row to add an entry.");
            hint.setForeground(Color.GRAY);
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
            bar.add(remove);
            bar.add(hint);
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(bar, BorderLayout.SOUTH);
        }

        void setRows(List<KeyValue> rows) {
            stopEditing();
            model.rows = rows;
            model.fireTableDataChanged();
        }

        void refresh() { model.fireTableDataChanged(); }

        void stopEditing() {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
        }

        void removeSelected() {
            stopEditing();
            if (model.rows == null) return;
            int[] sel = table.getSelectedRows();
            boolean removed = false;
            for (int i = sel.length - 1; i >= 0; i--) {
                if (sel[i] < model.rows.size()) { model.rows.remove(sel[i]); removed = true; }
            }
            if (!removed) return;
            model.fireTableDataChanged();
            model.onChange.run();
        }
    }

    static final class KvModel extends AbstractTableModel {
        final String[] columns;
        final Runnable onChange;
        List<KeyValue> rows;

        KvModel(String keyTitle, String valueTitle, Runnable onChange) {
            this.columns = new String[] {"", keyTitle, valueTitle};
            this.onChange = onChange;
        }

        @Override public int getRowCount() { return rows == null ? 0 : rows.size() + 1; }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return columns[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }

        @Override public boolean isCellEditable(int r, int c) { return rows != null && (r < rows.size() || c != 0); }

        @Override public Object getValueAt(int r, int c) {
            if (r >= rows.size()) return c == 0 ? Boolean.FALSE : "";
            KeyValue kv = rows.get(r);
            return c == 0 ? kv.enabled : c == 1 ? kv.key : kv.value;
        }

        @Override public void setValueAt(Object v, int r, int c) {
            if (rows == null) return;
            if (r >= rows.size()) {
                String s = String.valueOf(v);
                if (c == 0 || s.isEmpty()) return;
                rows.add(c == 1 ? new KeyValue(s, "", true) : new KeyValue("", s, true));
                fireTableRowsUpdated(r, r);
                fireTableRowsInserted(r + 1, r + 1);
            } else {
                KeyValue kv = rows.get(r);
                if (c == 0) kv.enabled = Boolean.TRUE.equals(v);
                else if (c == 1) kv.key = String.valueOf(v);
                else kv.value = String.valueOf(v);
                fireTableCellUpdated(r, c);
            }
            onChange.run();
        }
    }

    static final class Ui {
        static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};
        static final int DISPLAY_LIMIT = 10 * 1024 * 1024;
        static final Font MONO = monoFont();

        final Path file;
        Workspace ws;
        String loadProblem;
        boolean saveDisabled;
        final JFrame frame = new JFrame(APP);
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        final DefaultTreeModel treeModel = new DefaultTreeModel(root);
        final JTree tree = new JTree(treeModel);
        final JComboBox<String> envCombo = new JComboBox<>();
        final JCheckBox insecureBox = new JCheckBox("Ignore SSL errors");
        final JLabel statusBar = new JLabel(" ");
        final javax.swing.Timer saveTimer = new javax.swing.Timer(700, e -> save());
        final Map<Request, Response> results = new HashMap<>();
        final Map<Request, CompletableFuture<Response>> inFlight = new HashMap<>();
        RequestEditor editor;
        ResponseView responseView;
        DefaultMutableTreeNode currentNode;
        boolean updatingEnvCombo;
        String lastSaveError;
        File lastDir;

        Ui(Path file) {
            this.file = file;
            try {
                ws = Storage.load(file);
            } catch (Exception e) {
                ws = new Workspace();
                Path aside = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
                try {
                    Files.move(file, aside);
                    loadProblem = "Could not read " + file + ":\n" + e.getMessage()
                            + "\n\nThe file was renamed to " + aside.getFileName() + " and an empty workspace was started.";
                } catch (IOException moveError) {
                    saveDisabled = true;
                    loadProblem = "Could not read " + file + ":\n" + e.getMessage()
                            + "\n\nSaving is disabled for this session so the file is not overwritten.";
                }
            }
        }

        static Font monoFont() {
            Set<String> fonts = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String name : List.of("Cascadia Mono", "Consolas", "JetBrains Mono", "Menlo", "DejaVu Sans Mono"))
                if (fonts.contains(name)) return new Font(name, Font.PLAIN, 13);
            return new Font(Font.MONOSPACED, Font.PLAIN, 13);
        }

        void start() {
            saveTimer.setRepeats(false);
            editor = new RequestEditor();
            responseView = new ResponseView();

            JToolBar tb = new JToolBar();
            tb.setFloatable(false);
            tb.add(button("Import…", this::importFiles));
            tb.add(button("New Collection", this::newCollection));
            tb.add(button("New Request", this::newRequestFromToolbar));
            tb.addSeparator();
            tb.add(new JLabel(" Environment: "));
            envCombo.setMaximumSize(new Dimension(240, envCombo.getPreferredSize().height + 4));
            envCombo.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXXXXXX");
            tb.add(envCombo);
            tb.add(button("Manage…", this::manageEnvironments));
            tb.addSeparator();
            tb.add(button("Settings…", this::editSettings));
            tb.addSeparator();
            insecureBox.setSelected(ws.settings.insecureTls);
            insecureBox.setToolTipText("Skip SSL certificate and hostname verification for all requests (like curl -k)");
            insecureBox.addActionListener(e -> {
                ws.settings.insecureTls = insecureBox.isSelected();
                changed();
            });
            tb.add(insecureBox);

            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setCellRenderer(new ItemRenderer());
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.addTreeSelectionListener(e -> selectionChanged());
            tree.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
                @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            });
            bind(tree, "DELETE", () -> { if (selectedNode() != null) delete(selectedNode()); });
            bind(tree, "F2", () -> { if (selectedNode() != null) rename(selectedNode()); });
            for (Collection c : ws.collections) root.add(buildNode(c));
            treeModel.reload();
            for (int i = 0; i < root.getChildCount(); i++)
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(i)).getPath()));

            refreshEnvCombo();
            envCombo.addActionListener(e -> {
                if (updatingEnvCombo) return;
                int i = envCombo.getSelectedIndex();
                ws.activeEnvironment = i <= 0 ? "" : ws.environments.get(i - 1).name;
                changed();
            });

            JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor.panel, responseView.panel);
            right.setResizeWeight(0.45);
            right.setContinuousLayout(true);
            JScrollPane treeScroll = new JScrollPane(tree);
            treeScroll.setMinimumSize(new Dimension(150, 100));
            JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, right);
            main.setDividerLocation(290);
            main.setContinuousLayout(true);
            statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            statusBar.setForeground(Color.GRAY);

            frame.setLayout(new BorderLayout());
            frame.add(tb, BorderLayout.NORTH);
            frame.add(main, BorderLayout.CENTER);
            frame.add(statusBar, BorderLayout.SOUTH);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { exit(); }
            });
            frame.getRootPane().registerKeyboardAction(e -> sendOrCancel(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
            restoreBounds();
            frame.setVisible(true);
            SwingUtilities.invokeLater(() -> right.setDividerLocation(0.45));
            statusBar.setText("Workspace: " + file);
            if (loadProblem != null) JOptionPane.showMessageDialog(frame, loadProblem, APP, JOptionPane.WARNING_MESSAGE);
        }

        // ---------------------------------------------------------------- helpers

        static Action action(Runnable r) {
            return new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { r.run(); }
            };
        }

        static JButton button(String text, Runnable r) {
            JButton b = new JButton(text);
            b.addActionListener(e -> r.run());
            return b;
        }

        static void menuItem(JPopupMenu m, String text, Runnable r) {
            JMenuItem it = new JMenuItem(text);
            it.addActionListener(e -> r.run());
            m.add(it);
        }

        static void bind(JComponent c, String key, Runnable r) {
            String id = "rc-" + key;
            c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), id);
            c.getActionMap().put(id, action(r));
        }

        static void onTextChange(JTextComponent c, Runnable r) {
            c.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { r.run(); }
                @Override public void removeUpdate(DocumentEvent e) { r.run(); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
        }

        String ask(String label, String initial) {
            Object v = JOptionPane.showInputDialog(frame, label + ":", APP, JOptionPane.PLAIN_MESSAGE, null, null, initial);
            if (v == null || v.toString().isBlank()) return null;
            return v.toString().trim();
        }

        boolean confirm(String message) {
            return JOptionPane.showConfirmDialog(frame, message, APP, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                    == JOptionPane.OK_OPTION;
        }

        String uniqueEnvName(String base) {
            Set<String> names = new HashSet<>();
            for (Environment e : ws.environments) names.add(e.name);
            if (!names.contains(base)) return base;
            for (int i = 2; ; i++) if (!names.contains(base + " (" + i + ")")) return base + " (" + i + ")";
        }

        // ---------------------------------------------------------------- persistence

        void changed() {
            saveTimer.restart();
        }

        void save() {
            saveTimer.stop();
            if (saveDisabled) return;
            try {
                Storage.save(ws, file);
                statusBar.setText("Saved " + file);
                lastSaveError = null;
            } catch (IOException e) {
                String msg = e.toString();
                statusBar.setText("Save failed: " + msg);
                if (!msg.equals(lastSaveError)) {
                    lastSaveError = msg;
                    JOptionPane.showMessageDialog(frame, "Could not save the workspace:\n" + msg, APP, JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        void exit() {
            editor.commit();
            boolean max = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
            Rectangle b = frame.getBounds();
            if (max && ws.window != null && ws.window.length >= 4)
                ws.window = new int[] {ws.window[0], ws.window[1], ws.window[2], ws.window[3], 1};
            else ws.window = new int[] {b.x, b.y, b.width, b.height, max ? 1 : 0};
            for (CompletableFuture<Response> f : inFlight.values()) f.cancel(true);
            save();
            frame.dispose();
            System.exit(0);
        }

        void restoreBounds() {
            int[] w = ws.window;
            if (w != null && w.length >= 4 && w[2] >= 400 && w[3] >= 300 && onScreen(new Rectangle(w[0], w[1], w[2], w[3]))) {
                frame.setBounds(w[0], w[1], w[2], w[3]);
                if (w.length > 4 && w[4] == 1) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            } else {
                frame.setSize(1240, 800);
                frame.setLocationRelativeTo(null);
            }
        }

        static boolean onScreen(Rectangle r) {
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (gd.getDefaultConfiguration().getBounds().intersects(r)) return true;
            return false;
        }

        // ---------------------------------------------------------------- tree

        DefaultMutableTreeNode buildNode(Item it) {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(it);
            if (it instanceof Folder f) for (Item child : f.items) n.add(buildNode(child));
            return n;
        }

        DefaultMutableTreeNode selectedNode() {
            TreePath p = tree.getSelectionPath();
            return p == null ? null : (DefaultMutableTreeNode) p.getLastPathComponent();
        }

        void select(DefaultMutableTreeNode n) {
            TreePath p = new TreePath(n.getPath());
            tree.setSelectionPath(p);
            tree.scrollPathToVisible(p);
        }

        Collection collectionOf(DefaultMutableTreeNode n) {
            TreeNode[] path = n.getPath();
            return path.length > 1 ? (Collection) ((DefaultMutableTreeNode) path[1]).getUserObject() : null;
        }

        String breadcrumb(DefaultMutableTreeNode n) {
            StringJoiner j = new StringJoiner("  ›  ");
            for (TreeNode t : n.getPath())
                if (((DefaultMutableTreeNode) t).getUserObject() instanceof Item it) j.add(it.name);
            return j.toString();
        }

        @SuppressWarnings("unchecked")
        List<Item> siblings(DefaultMutableTreeNode n) {
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) n.getParent();
            return parent == root ? (List<Item>) (List<?>) ws.collections : ((Folder) parent.getUserObject()).items;
        }

        void selectionChanged() {
            editor.commit();
            DefaultMutableTreeNode n = selectedNode();
            currentNode = n;
            if (n != null && n.getUserObject() instanceof Request r) {
                editor.load(r, breadcrumb(n));
                responseView.show(results.get(r), inFlight.containsKey(r));
            } else {
                editor.showEmpty();
                responseView.show(null, false);
            }
        }

        void maybePopup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            TreePath p = tree.getPathForLocation(e.getX(), e.getY());
            if (p != null) tree.setSelectionPath(p);
            popupFor(p == null ? null : (DefaultMutableTreeNode) p.getLastPathComponent()).show(tree, e.getX(), e.getY());
        }

        JPopupMenu popupFor(DefaultMutableTreeNode n) {
            JPopupMenu m = new JPopupMenu();
            if (n == null) {
                menuItem(m, "New Collection…", this::newCollection);
                menuItem(m, "Import…", this::importFiles);
                return m;
            }
            Object o = n.getUserObject();
            if (o instanceof Folder) {
                menuItem(m, "New Request…", () -> newRequest(n));
                menuItem(m, "New Folder…", () -> {
                    String name = ask("Folder name", "New Folder");
                    if (name != null) addItem(n, new Folder(name));
                });
                m.addSeparator();
            }
            if (o instanceof Collection c) {
                menuItem(m, "Variables…", () -> editVariables(c));
                m.addSeparator();
            }
            menuItem(m, "Rename…", () -> rename(n));
            menuItem(m, "Duplicate", () -> duplicate(n));
            menuItem(m, "Move Up", () -> move(n, -1));
            menuItem(m, "Move Down", () -> move(n, 1));
            m.addSeparator();
            menuItem(m, "Delete", () -> delete(n));
            return m;
        }

        void newCollection() {
            String name = ask("Collection name", "New Collection");
            if (name == null) return;
            Collection c = new Collection(name);
            ws.collections.add(c);
            DefaultMutableTreeNode n = buildNode(c);
            treeModel.insertNodeInto(n, root, root.getChildCount());
            select(n);
            changed();
        }

        void newRequestFromToolbar() {
            DefaultMutableTreeNode n = selectedNode();
            if (n != null && n.getUserObject() instanceof Request) n = (DefaultMutableTreeNode) n.getParent();
            if (n == null || n == root) {
                if (root.getChildCount() == 0) {
                    Collection c = new Collection("My Collection");
                    ws.collections.add(c);
                    n = buildNode(c);
                    treeModel.insertNodeInto(n, root, 0);
                    changed();
                } else {
                    n = (DefaultMutableTreeNode) root.getChildAt(0);
                }
            }
            newRequest(n);
        }

        void newRequest(DefaultMutableTreeNode parent) {
            String name = ask("Request name", "New Request");
            if (name != null) addItem(parent, new Request(name));
        }

        void addItem(DefaultMutableTreeNode parent, Item it) {
            ((Folder) parent.getUserObject()).items.add(it);
            DefaultMutableTreeNode n = buildNode(it);
            treeModel.insertNodeInto(n, parent, parent.getChildCount());
            select(n);
            changed();
        }

        void rename(DefaultMutableTreeNode n) {
            Item it = (Item) n.getUserObject();
            String name = ask("Name", it.name);
            if (name == null || name.equals(it.name)) return;
            it.name = name;
            treeModel.nodeChanged(n);
            if (n == currentNode) editor.breadcrumb.setText(breadcrumb(n));
            changed();
        }

        void duplicate(DefaultMutableTreeNode n) {
            Item it = (Item) n.getUserObject();
            Item copy = Storage.copy(it);
            copy.name = it.name + " Copy";
            List<Item> list = siblings(n);
            int idx = list.indexOf(it) + 1;
            list.add(idx, copy);
            DefaultMutableTreeNode c = buildNode(copy);
            treeModel.insertNodeInto(c, (DefaultMutableTreeNode) n.getParent(), idx);
            select(c);
            changed();
        }

        void move(DefaultMutableTreeNode n, int delta) {
            Item it = (Item) n.getUserObject();
            List<Item> list = siblings(n);
            int idx = list.indexOf(it), to = idx + delta;
            if (to < 0 || to >= list.size()) return;
            boolean expanded = tree.isExpanded(new TreePath(n.getPath()));
            list.remove(idx);
            list.add(to, it);
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) n.getParent();
            treeModel.removeNodeFromParent(n);
            treeModel.insertNodeInto(n, parent, to);
            if (expanded) tree.expandPath(new TreePath(n.getPath()));
            select(n);
            changed();
        }

        void delete(DefaultMutableTreeNode n) {
            Item it = (Item) n.getUserObject();
            String what = it instanceof Request ? "request" : it instanceof Collection ? "collection" : "folder";
            if (!confirm("Delete " + what + " '" + it.name + "'?")) return;
            siblings(n).remove(it);
            treeModel.removeNodeFromParent(n);
            changed();
        }

        final class ItemRenderer extends DefaultTreeCellRenderer {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
                Object o = ((DefaultMutableTreeNode) value).getUserObject();
                if (o instanceof Request r) {
                    setIcon(null);
                    String m = html(r.method);
                    setText("<html><b>" + (sel ? m : "<font color='" + methodColor(r.method) + "'>" + m + "</font>")
                            + "</b>&nbsp;" + html(r.name) + "</html>"); // coloured text is unreadable on the selection colour
                } else if (o instanceof Collection c) {
                    setIcon(exp ? getOpenIcon() : getClosedIcon());
                    setText("<html><b>" + html(c.name) + "</b></html>");
                } else if (o instanceof Folder f) {
                    setIcon(exp ? getOpenIcon() : getClosedIcon());
                    setText(f.name);
                }
                return this;
            }
        }

        static String methodColor(String method) {
            return switch (method) {
                case "GET" -> "#2e7d32";
                case "POST" -> "#e65100";
                case "PUT" -> "#1565c0";
                case "PATCH" -> "#6a1b9a";
                case "DELETE" -> "#c62828";
                default -> "#555555";
            };
        }

        // ---------------------------------------------------------------- environments, variables, settings

        void refreshEnvCombo() {
            updatingEnvCombo = true;
            try {
                envCombo.removeAllItems();
                envCombo.addItem("No environment");
                for (Environment e : ws.environments) envCombo.addItem(e.name);
                Environment active = ws.activeEnv();
                if (active == null) ws.activeEnvironment = "";
                envCombo.setSelectedIndex(active == null ? 0 : ws.environments.indexOf(active) + 1);
            } finally {
                updatingEnvCombo = false;
            }
        }

        void manageEnvironments() {
            editor.commit();
            JDialog d = new JDialog(frame, "Environments", true);
            d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            DefaultListModel<Environment> model = new DefaultListModel<>();
            ws.environments.forEach(model::addElement);
            JList<Environment> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            KvTable table = new KvTable("Variable", "Value", this::changed);
            table.setRows(null);
            list.addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                Environment env = list.getSelectedValue();
                table.setRows(env == null ? null : env.vars);
            });
            JButton add = button("Add", () -> {
                String name = ask("Environment name", "New Environment");
                if (name == null) return;
                Environment env = new Environment(uniqueEnvName(name));
                ws.environments.add(env);
                model.addElement(env);
                list.setSelectedValue(env, true);
                changed();
            });
            JButton rename = button("Rename", () -> {
                Environment env = list.getSelectedValue();
                if (env == null) return;
                String name = ask("Environment name", env.name);
                if (name == null || name.equals(env.name)) return;
                boolean active = env.name.equals(ws.activeEnvironment);
                env.name = uniqueEnvName(name);
                if (active) ws.activeEnvironment = env.name;
                list.repaint();
                changed();
            });
            JButton duplicate = button("Duplicate", () -> {
                Environment env = list.getSelectedValue();
                if (env == null) return;
                table.stopEditing();
                Environment copy = new Environment(uniqueEnvName(env.name + " Copy"));
                env.vars.forEach(v -> copy.vars.add(v.copy()));
                ws.environments.add(copy);
                model.addElement(copy);
                list.setSelectedValue(copy, true);
                changed();
            });
            JButton delete = button("Delete", () -> {
                Environment env = list.getSelectedValue();
                if (env == null || !confirm("Delete environment '" + env.name + "'?")) return;
                ws.environments.remove(env);
                model.removeElement(env);
                if (env.name.equals(ws.activeEnvironment)) ws.activeEnvironment = "";
                changed();
            });
            JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 0));
            buttons.add(add);
            buttons.add(rename);
            buttons.add(duplicate);
            buttons.add(delete);
            JPanel left = new JPanel(new BorderLayout(0, 6));
            left.add(new JScrollPane(list), BorderLayout.CENTER);
            left.add(buttons, BorderLayout.SOUTH);
            left.setPreferredSize(new Dimension(360, 0));
            JPanel content = new JPanel(new BorderLayout(10, 10));
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            content.add(left, BorderLayout.WEST);
            content.add(table, BorderLayout.CENTER);
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            south.add(button("Close", d::dispose));
            content.add(south, BorderLayout.SOUTH);
            d.setContentPane(content);
            Environment active = ws.activeEnv();
            if (active != null) list.setSelectedValue(active, true);
            else if (!model.isEmpty()) list.setSelectedIndex(0);
            d.setSize(980, 480);
            d.setLocationRelativeTo(frame);
            d.setVisible(true); // modal: returns when closed
            table.stopEditing();
            refreshEnvCombo();
            changed();
        }

        void editVariables(Collection c) {
            JDialog d = new JDialog(frame, "Variables - " + c.name, true);
            d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            KvTable table = new KvTable("Variable", "Value", this::changed);
            table.setRows(c.variables);
            JLabel help = new JLabel("Collection variables are used when the active environment doesn't define a variable.");
            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            content.add(help, BorderLayout.NORTH);
            content.add(table, BorderLayout.CENTER);
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            south.add(button("Close", d::dispose));
            content.add(south, BorderLayout.SOUTH);
            d.setContentPane(content);
            d.setSize(720, 420);
            d.setLocationRelativeTo(frame);
            d.setVisible(true);
            table.stopEditing();
        }

        void editSettings() {
            Settings s = ws.settings;
            JSpinner timeout = new JSpinner(new SpinnerNumberModel(s.timeoutSeconds, 1, 3600, 1));
            JCheckBox redirects = new JCheckBox("Follow redirects", s.followRedirects);
            JCheckBox http2 = new JCheckBox("Use HTTP/2 when the server supports it", s.http2);
            JCheckBox insecure = new JCheckBox("Ignore SSL errors: skip certificate and hostname verification (like curl -k)", s.insecureTls);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.add(new JLabel("Timeout (seconds): "));
            row.add(timeout);
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
            p.add(row);
            p.add(redirects);
            p.add(http2);
            p.add(insecure);
            if (JOptionPane.showConfirmDialog(frame, p, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) return;
            s.timeoutSeconds = (Integer) timeout.getValue();
            s.followRedirects = redirects.isSelected();
            s.http2 = http2.isSelected();
            s.insecureTls = insecure.isSelected();
            insecureBox.setSelected(s.insecureTls);
            changed();
        }

        // ---------------------------------------------------------------- import

        void importFiles() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new FileNameExtensionFilter("Postman collections and environments (*.json)", "json"));
            if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            lastDir = fc.getCurrentDirectory();
            StringBuilder report = new StringBuilder();
            DefaultMutableTreeNode lastNode = null;
            for (File f : fc.getSelectedFiles()) {
                report.append(f.getName()).append('\n');
                try {
                    String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    PostmanImporter.ImportResult r = PostmanImporter.importText(text);
                    if (r.collection != null) {
                        ws.collections.add(r.collection);
                        lastNode = buildNode(r.collection);
                        treeModel.insertNodeInto(lastNode, root, root.getChildCount());
                        report.append("  Collection '").append(r.collection.name).append("': ")
                                .append(countRequests(r.collection)).append(" requests\n");
                    } else {
                        r.environment.name = uniqueEnvName(r.environment.name);
                        ws.environments.add(r.environment);
                        report.append("  Environment '").append(r.environment.name).append("': ")
                                .append(r.environment.vars.size()).append(" variables\n");
                    }
                    r.skipped.forEach((what, n) -> report.append("  Skipped: ").append(what)
                            .append(n > 1 ? " (" + n + "×)" : "").append('\n'));
                } catch (Exception e) {
                    report.append("  Failed: ").append(e.getMessage()).append('\n');
                }
            }
            if (lastNode != null) {
                tree.expandPath(new TreePath(lastNode.getPath()));
                select(lastNode);
            }
            refreshEnvCombo();
            changed();
            JTextArea area = new JTextArea(report.toString().trim());
            area.setEditable(false);
            area.setFont(MONO);
            JScrollPane sp = new JScrollPane(area);
            sp.setPreferredSize(new Dimension(620, 280));
            JOptionPane.showMessageDialog(frame, sp, "Import", JOptionPane.INFORMATION_MESSAGE);
        }

        // ---------------------------------------------------------------- sending

        void sendOrCancel() {
            Request r = editor.req;
            if (r == null) return;
            CompletableFuture<Response> running = inFlight.get(r);
            if (running != null) { running.cancel(true); return; }
            editor.commit();
            Collection c = collectionOf(currentNode);
            Environment env = ws.activeEnv();
            Engine.Prepared p;
            try {
                p = Engine.prepare(r, Engine.variables(c, env));
            } catch (IllegalArgumentException e) {
                Response failed = Response.failed("", e.getMessage(), List.of());
                results.put(r, failed);
                responseView.show(failed, false);
                return;
            }
            CompletableFuture<Response> f = Engine.send(p, ws.settings);
            inFlight.put(r, f);
            editor.updateSendButton();
            responseView.show(null, true);
            f.whenComplete((res, ex) -> SwingUtilities.invokeLater(() -> finished(r, c, env, p, res, ex)));
        }

        void finished(Request r, Collection c, Environment env, Engine.Prepared p, Response res, Throwable ex) {
            inFlight.remove(r);
            Response shown;
            if (res != null) {
                List<String> log = Engine.applyPostActions(r, res, c, env);
                res.messages.addAll(log);
                if (!log.isEmpty()) changed();
                shown = res;
            } else {
                String error = Engine.describe(ex);
                if (!ws.settings.insecureTls && Engine.isTlsError(ex))
                    error += "\n\nThe SSL/TLS connection failed. If the server uses a self-signed, expired or mismatched"
                            + "\ncertificate, tick \"Ignore SSL errors\" in the toolbar (like curl -k) and send again.";
                shown = Response.failed(p.summary(), error, p.messages);
            }
            results.put(r, shown);
            if (editor.req == r) {
                responseView.show(shown, false);
                editor.updateSendButton();
            }
        }

        // ---------------------------------------------------------------- request editor

        final class RequestEditor {
            final JPanel panel = new JPanel(new CardLayout());
            final JLabel breadcrumb = new JLabel(" ");
            final JComboBox<String> method = new JComboBox<>(METHODS);
            final JTextField url = new JTextField();
            final JButton send = new JButton("Send");
            final KvTable params = new KvTable("Key", "Value", this::paramsChanged);
            final KvTable headers = new KvTable("Header", "Value", this::tableChanged);
            final KvTable post = new KvTable("Variable", "JSON path (empty = whole body)", this::tableChanged);
            final JRadioButton bodyNone = new JRadioButton("None");
            final JRadioButton bodyText = new JRadioButton("Text");
            final JRadioButton bodyJson = new JRadioButton("JSON");
            final JButton format = new JButton("Format JSON");
            final JTextArea body = new JTextArea();
            final UndoManager undo = new UndoManager();
            final JTabbedPane tabs = new JTabbedPane();
            Request req;
            boolean loading, syncing;

            RequestEditor() {
                method.setEditable(true);
                method.setPrototypeDisplayValue("OPTIONS  ");
                method.addActionListener(e -> methodChanged());
                url.setFont(MONO);
                onTextChange(url, this::urlChanged);
                url.addActionListener(e -> sendOrCancel());
                send.addActionListener(e -> sendOrCancel());
                send.setPreferredSize(new Dimension(90, send.getPreferredSize().height));
                send.setToolTipText("Send (Ctrl+Enter)");

                JPanel line = new JPanel(new BorderLayout(6, 0));
                line.add(method, BorderLayout.WEST);
                line.add(url, BorderLayout.CENTER);
                line.add(send, BorderLayout.EAST);
                breadcrumb.setForeground(Color.GRAY);
                JPanel top = new JPanel(new BorderLayout(0, 6));
                top.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
                top.add(breadcrumb, BorderLayout.NORTH);
                top.add(line, BorderLayout.CENTER);

                ButtonGroup group = new ButtonGroup();
                for (JRadioButton b : List.of(bodyNone, bodyText, bodyJson)) {
                    group.add(b);
                    b.addActionListener(e -> bodyModeChanged());
                }
                format.addActionListener(e -> formatBody());
                body.setFont(MONO);
                body.setTabSize(2);
                onTextChange(body, () -> {
                    if (loading || req == null) return;
                    req.body = body.getText();
                    changed();
                });
                body.getDocument().addUndoableEditListener(e -> { if (!loading) undo.addEdit(e.getEdit()); });
                bind(body, "control Z", () -> { if (undo.canUndo()) undo.undo(); });
                bind(body, "control Y", () -> { if (undo.canRedo()) undo.redo(); });
                JPanel bodyBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
                bodyBar.add(bodyNone);
                bodyBar.add(bodyText);
                bodyBar.add(bodyJson);
                bodyBar.add(format);
                JPanel bodyPanel = new JPanel(new BorderLayout());
                bodyPanel.add(bodyBar, BorderLayout.NORTH);
                bodyPanel.add(new JScrollPane(body), BorderLayout.CENTER);

                JLabel help = new JLabel("<html>After a 2xx response, each enabled row stores a value in the active environment "
                        + "(or in the collection variables when no environment is selected).<br>"
                        + "JSON path examples: <b>token</b>, <b>data.items[0].id</b>, <b>$.result</b>. "
                        + "Leave the path empty to store the whole response body.</html>");
                help.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
                JPanel postPanel = new JPanel(new BorderLayout());
                postPanel.add(help, BorderLayout.NORTH);
                postPanel.add(post, BorderLayout.CENTER);

                tabs.addTab("Params", params);
                tabs.addTab("Headers", headers);
                tabs.addTab("Body", bodyPanel);
                tabs.addTab("Post-processing", postPanel);

                JPanel card = new JPanel(new BorderLayout());
                card.add(top, BorderLayout.NORTH);
                card.add(tabs, BorderLayout.CENTER);
                JLabel empty = new JLabel("Select a request, or use New Request / Import to get started.", SwingConstants.CENTER);
                empty.setForeground(Color.GRAY);
                panel.add(empty, "empty");
                panel.add(card, "editor");
                panel.setMinimumSize(new Dimension(300, 150));
            }

            void load(Request r, String crumb) {
                loading = true;
                try {
                    req = r;
                    breadcrumb.setText(crumb);
                    method.setSelectedItem(r.method);
                    url.setText(r.url());
                    url.setCaretPosition(0);
                    params.setRows(r.params);
                    headers.setRows(r.headers);
                    post.setRows(r.postActions);
                    body.setText(r.body);
                    body.setCaretPosition(0);
                    undo.discardAllEdits();
                    (r.bodyMode == BodyMode.JSON ? bodyJson : r.bodyMode == BodyMode.TEXT ? bodyText : bodyNone).setSelected(true);
                } finally {
                    loading = false;
                }
                updateBodyControls();
                updateTabTitles();
                updateSendButton();
                ((CardLayout) panel.getLayout()).show(panel, "editor");
            }

            void showEmpty() {
                req = null;
                ((CardLayout) panel.getLayout()).show(panel, "empty");
            }

            /** Pushes pending cell edits and a typed-but-unconfirmed method into the model. */
            void commit() {
                params.stopEditing();
                headers.stopEditing();
                post.stopEditing();
                if (req != null) {
                    String typed = String.valueOf(method.getEditor().getItem()).trim().toUpperCase(Locale.ROOT);
                    if (!typed.isEmpty() && !typed.equals(req.method)) {
                        req.method = typed;
                        if (currentNode != null) treeModel.nodeChanged(currentNode);
                        changed();
                    }
                }
            }

            void methodChanged() {
                if (loading || req == null) return;
                String m = String.valueOf(method.getSelectedItem()).trim().toUpperCase(Locale.ROOT);
                if (m.isEmpty() || m.equals(req.method)) return;
                req.method = m;
                if (currentNode != null) treeModel.nodeChanged(currentNode);
                changed();
            }

            void urlChanged() {
                if (loading || syncing || req == null) return;
                syncing = true;
                try {
                    req.setUrl(url.getText());
                    params.refresh();
                } finally {
                    syncing = false;
                }
                updateTabTitles();
                changed();
            }

            void paramsChanged() {
                if (loading || syncing || req == null) return;
                syncing = true;
                try {
                    url.setText(req.url());
                } finally {
                    syncing = false;
                }
                updateTabTitles();
                changed();
            }

            void tableChanged() {
                if (loading || req == null) return;
                updateTabTitles();
                changed();
            }

            void bodyModeChanged() {
                if (loading || req == null) return;
                req.bodyMode = bodyJson.isSelected() ? BodyMode.JSON : bodyText.isSelected() ? BodyMode.TEXT : BodyMode.NONE;
                updateBodyControls();
                updateTabTitles();
                changed();
            }

            void updateBodyControls() {
                body.setEnabled(req != null && req.bodyMode != BodyMode.NONE);
                format.setEnabled(req != null && req.bodyMode == BodyMode.JSON);
            }

            void formatBody() {
                try {
                    body.setText(Json.pretty(Json.parse(body.getText())));
                    body.setCaretPosition(0);
                } catch (JsonException e) {
                    JOptionPane.showMessageDialog(frame, "The body is not valid JSON:\n" + e.getMessage()
                            + "\n\n(Variables such as {{id}} must be inside quotes to format.)", APP, JOptionPane.WARNING_MESSAGE);
                }
            }

            void updateTabTitles() {
                if (req == null) return;
                tabs.setTitleAt(0, title("Params", req.params));
                tabs.setTitleAt(1, title("Headers", req.headers));
                tabs.setTitleAt(2, switch (req.bodyMode) {
                    case NONE -> "Body";
                    case TEXT -> "Body (Text)";
                    case JSON -> "Body (JSON)";
                });
                tabs.setTitleAt(3, title("Post-processing", req.postActions));
            }

            String title(String name, List<KeyValue> rows) {
                long n = rows.stream().filter(KeyValue::active).count();
                return n == 0 ? name : name + " (" + n + ")";
            }

            void updateSendButton() {
                send.setText(req != null && inFlight.containsKey(req) ? "Cancel" : "Send");
            }
        }

        // ---------------------------------------------------------------- response view

        final class ResponseView {
            final JPanel panel = new JPanel(new BorderLayout());
            final JLabel status = new JLabel(" ");
            final JLabel meta = new JLabel(" ");
            final JTextArea body = new JTextArea();
            final JCheckBox pretty = new JCheckBox("Pretty", true);
            final DefaultTableModel headerModel = new DefaultTableModel(new Object[] {"Header", "Value"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            final JTextArea log = new JTextArea();
            final JTabbedPane tabs = new JTabbedPane();
            Response shown;

            ResponseView() {
                status.setFont(status.getFont().deriveFont(Font.BOLD, status.getFont().getSize2D() + 3f));
                meta.setForeground(Color.GRAY);
                JLabel title = new JLabel("Response");
                title.setFont(title.getFont().deriveFont(Font.BOLD));
                JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
                head.add(title);
                head.add(status);
                head.add(meta);
                body.setEditable(false);
                body.setFont(MONO);
                body.setTabSize(2);
                pretty.addActionListener(e -> renderBody());
                pretty.setEnabled(false);
                JPanel bodyBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
                bodyBar.add(pretty);
                JPanel bodyPanel = new JPanel(new BorderLayout());
                bodyPanel.add(bodyBar, BorderLayout.NORTH);
                bodyPanel.add(new JScrollPane(body), BorderLayout.CENTER);
                JTable headerTable = new JTable(headerModel);
                headerTable.setFillsViewportHeight(true);
                log.setEditable(false);
                log.setFont(MONO);
                tabs.addTab("Body", bodyPanel);
                tabs.addTab("Headers", new JScrollPane(headerTable));
                tabs.addTab("Log", new JScrollPane(log));
                panel.add(head, BorderLayout.NORTH);
                panel.add(tabs, BorderLayout.CENTER);
                panel.setMinimumSize(new Dimension(300, 120));
            }

            void show(Response r, boolean sending) {
                shown = sending ? null : r;
                headerModel.setRowCount(0);
                tabs.setTitleAt(1, "Headers");
                tabs.setTitleAt(2, "Log");
                pretty.setEnabled(false);
                status.setForeground(Color.GRAY);
                meta.setText(" ");
                if (sending || r == null) {
                    status.setText(sending ? "Sending…" : " ");
                    body.setText("");
                    log.setText("");
                    return;
                }
                if (r.error != null) {
                    status.setText("Error");
                    status.setForeground(new Color(0xc62828));
                    body.setText(r.error);
                    body.setCaretPosition(0);
                } else {
                    status.setText((r.status + " " + reasonPhrase(r.status)).trim());
                    status.setForeground(statusColor(r.status));
                    meta.setText(r.millis + " ms   " + formatSize(r.bytes.length) + "   " + r.version);
                    for (String[] h : r.headers) headerModel.addRow(h);
                    tabs.setTitleAt(1, "Headers (" + r.headers.size() + ")");
                    renderBody();
                }
                StringBuilder b = new StringBuilder();
                if (!r.requestSummary.isEmpty()) b.append("Request:\n").append(r.requestSummary).append("\n");
                if (!r.url.isEmpty() && !r.requestSummary.contains(r.url)) b.append("\nFinal URL: ").append(r.url).append("\n");
                if (!r.messages.isEmpty()) {
                    b.append("\nMessages:\n");
                    for (String m : r.messages) b.append("- ").append(m).append('\n');
                    tabs.setTitleAt(2, "Log (" + r.messages.size() + ")");
                }
                log.setText(b.toString());
                log.setCaretPosition(0);
            }

            void renderBody() {
                Response r = shown;
                if (r == null || r.error != null) return;
                pretty.setEnabled(r.pretty != null);
                String t;
                if (r.text == null)
                    t = "<binary body: " + formatSize(r.bytes.length) + (r.contentType.isEmpty() ? "" : ", " + r.contentType) + ">";
                else t = pretty.isSelected() && r.pretty != null ? r.pretty : r.text;
                if (t.length() > DISPLAY_LIMIT)
                    t = t.substring(0, DISPLAY_LIMIT) + "\n\n… (truncated: showing the first " + formatSize(DISPLAY_LIMIT) + " of characters)";
                body.setText(t);
                body.setCaretPosition(0);
            }

            Color statusColor(int s) {
                if (s >= 200 && s < 300) return new Color(0x2e7d32);
                if (s >= 300 && s < 400) return new Color(0x1565c0);
                if (s >= 400 && s < 500) return new Color(0xe65100);
                return new Color(0xc62828);
            }
        }
    }

    // =====================================================================================
    // Self-tests (run with --selftest; needs the full JDK because it uses jdk.httpserver)
    // =====================================================================================

    static final class SelfTest {
        interface Body { void run() throws Exception; }

        int passed, failed;

        static int run() {
            SelfTest t = new SelfTest();
            t.section("json", t::json);
            t.section("urls", t::urls);
            t.section("variables", t::variables);
            t.section("postman", t::postman);
            t.section("storage", t::storage);
            t.section("http", t::http);
            t.section("https", t::https);
            System.out.println((t.failed == 0 ? "OK" : "FAILED") + ": " + t.passed + " passed, " + t.failed + " failed");
            return t.failed == 0 ? 0 : 1;
        }

        void section(String name, Body b) {
            try { b.run(); }
            catch (Throwable e) {
                failed++;
                System.out.println("FAIL section " + name + " threw:");
                e.printStackTrace(System.out);
            }
        }

        void eq(String name, Object expected, Object actual) {
            if (Objects.equals(expected, actual)) passed++;
            else {
                failed++;
                System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  actual:   " + actual);
            }
        }

        void check(String name, boolean ok) { eq(name, true, ok); }

        void fails(String name, Runnable r) {
            try {
                r.run();
                failed++;
                System.out.println("FAIL " + name + ": no exception");
            } catch (RuntimeException e) {
                passed++;
            }
        }

        static String header(Request r, String name) {
            for (KeyValue h : r.headers) if (h.key.equalsIgnoreCase(name)) return h.value;
            return null;
        }

        static String kvs(List<KeyValue> list) {
            return list.stream().map(p -> p.key + "=" + p.value + (p.enabled ? "" : "(off)")).collect(Collectors.joining("|"));
        }

        void json() {
            String src = "{\"a\":1,\"b\":[true,false,null],\"c\":\"x\\u00e9\\n\\\"q\\\"\",\"d\":12345678901234567890.1234567890,\"e\":{}}";
            eq("json round trip", "{\"a\":1,\"b\":[true,false,null],\"c\":\"xé\\n\\\"q\\\"\",\"d\":12345678901234567890.1234567890,\"e\":{}}",
                    Json.write(Json.parse(src)));
            eq("json pretty", "{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {}\n}", Json.pretty(Json.parse("{\"a\":[1,2],\"b\":{}}")));
            eq("json key order", List.of("z", "a", "m"), List.copyOf(Json.obj(Json.parse("{\"z\":1,\"a\":2,\"m\":3}")).keySet()));
            eq("json surrogate pair", "\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
            eq("json BOM and whitespace", List.of(), Json.parse("\uFEFF [ ] "));
            eq("json exponent", "-1.5E+3", Json.write(Json.parse("-1.5E+3")));
            for (String bad : List.of("", "{", "[1,]", "{\"a\" 1}", "tru", "\"abc", "1 2", "{\"a\":1,}", "-", "01x", "\"\\x\""))
                fails("json rejects " + bad, () -> Json.parse(bad));

            Object doc = Json.parse("{\"data\":{\"items\":[{\"id\":7,\"name\":\"n\"}]},\"s\":\"str\"}");
            eq("path nested", "7", Json.asText(Json.at(doc, "data.items[0].id")));
            eq("path $ prefix", "{\"id\":7,\"name\":\"n\"}", Json.asText(Json.at(doc, "$.data.items[0]")));
            eq("path quoted keys", "n", Json.asText(Json.at(doc, "data[\"items\"][0]['name']")));
            eq("path string is raw", "str", Json.asText(Json.at(doc, "s")));
            fails("path missing key", () -> Json.at(doc, "data.nope"));
            fails("path bad index", () -> Json.at(doc, "data.items[5]"));
        }

        void urls() {
            Request r = new Request("u");
            r.setUrl("http://h/p?a=1&b=&c");
            eq("url base", "http://h/p", r.baseUrl);
            eq("url params", "a=1|b=|c=", kvs(r.params));
            eq("url rebuild", "http://h/p?a=1&b&c", r.url());
            r.params.get(1).enabled = false;
            eq("url omits disabled", "http://h/p?a=1&c", r.url());
            r.setUrl("http://h/p?x=9");
            eq("url keeps disabled params", "x=9|b=(off)", kvs(r.params));
            r.setUrl("http://h/q");
            eq("url without query", "http://h/q", r.url());

            eq("encode illegal only", "http://h/a%20b?q=%7Bx%7D&r=%41&s=%25zz", Urls.encode("http://h/a b?q={x}&r=%41&s=%zz"));
            eq("encode ipv6 host", "http://[::1]:8080/p%5B0%5D", Urls.encode("http://[::1]:8080/p[0]"));
            eq("encode unicode", "http://h/%C3%A9?q=%D0%B6", Urls.encode("http://h/é?q=ж"));
            eq("encode second hash", "http://h/#a%23b", Urls.encode("http://h/#a#b"));
        }

        void variables() {
            Map<String, String> vars = Map.of("host", "example.com", "path", "{{p2}}", "p2", "v1");
            Set<String> unresolved = new TreeSet<>();
            eq("resolve nested", "https://example.com/v1/{{missing}}",
                    Engine.resolve("https://{{host}}/{{ path }}/{{missing}}", vars, unresolved));
            eq("unresolved names", Set.of("missing"), unresolved);
            eq("dynamic guid", 36, Engine.resolve("{{$guid}}", Map.of(), new HashSet<>()).length());

            Collection c = new Collection("c");
            c.variables.add(new KeyValue("host", "coll.example", true));
            c.variables.add(new KeyValue("only", "c", true));
            Environment e = new Environment("e");
            e.vars.add(new KeyValue("host", "env.example", true));
            e.vars.add(new KeyValue("only", "off", false));
            Map<String, String> v = Engine.variables(c, e);
            eq("environment wins", "env.example", v.get("host"));
            eq("disabled ignored", "c", v.get("only"));

            Request r = new Request("p");
            r.setUrl("{{host}}/x");
            Engine.Prepared p = Engine.prepare(r, v);
            eq("default scheme", "http://env.example/x", p.uri.toString());
            r.setUrl("{{nohost}}/x");
            fails("unresolved host rejected", () -> Engine.prepare(r, v));
            r.setUrl("ftp://x/y");
            fails("ftp rejected", () -> Engine.prepare(r, v));
        }

        static final String SAMPLE = """
                {
                  "info": {"name": "Sample", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{token}}", "type": "string"}]},
                  "variable": [{"key": "baseUrl", "value": "https://api.example.com"}],
                  "event": [{"listen": "prerequest", "script": {"exec": ["console.log('x')"]}}],
                  "item": [
                    {"name": "Users", "item": [
                      {"name": "List users",
                       "request": {"method": "get",
                         "header": [{"key": "Accept", "value": "application/json"}, {"key": "X-Off", "value": "1", "disabled": true}],
                         "url": {"raw": "{{baseUrl}}/users?page=1", "host": ["{{baseUrl}}"], "path": ["users"],
                                 "query": [{"key": "page", "value": "1"}, {"key": "size", "value": "10", "disabled": true}]}},
                       "event": [{"listen": "test", "script": {"exec": [
                         "// store ids",
                         "var jsonData = pm.response.json();",
                         "pm.environment.set(\\"firstId\\", jsonData.items[0].id);",
                         "pm.collectionVariables.set('raw', pm.response.text());",
                         "pm.environment.set(\\"whole\\", JSON.stringify(pm.response.json()));",
                         "pm.test(\\"ok\\", function () { pm.response.to.have.status(200); });"]}}]},
                      {"name": "Create user",
                       "request": {"method": "POST",
                         "auth": {"type": "basic", "basic": [{"key": "username", "value": "u"}, {"key": "password", "value": "p"}]},
                         "body": {"mode": "raw", "raw": "{\\"name\\": \\"{{name}}\\"}", "options": {"raw": {"language": "json"}}},
                         "url": "{{baseUrl}}/users"}}
                    ]},
                    {"name": "Upload", "request": {"method": "POST", "auth": {"type": "noauth"},
                      "body": {"mode": "formdata", "formdata": []}, "url": "{{baseUrl}}/upload"}},
                    {"name": "Plain", "request": "https://example.com/plain?x=1"},
                    {"name": "Digest", "request": {"method": "GET", "auth": {"type": "digest", "digest": []}, "url": "https://example.com"}},
                    {"name": "Key", "request": {"method": "GET", "url": "https://example.com",
                      "auth": {"type": "apikey", "apikey": [{"key": "key", "value": "k"}, {"key": "value", "value": "v"}, {"key": "in", "value": "query"}]}}}
                  ]
                }
                """;

        void postman() {
            PostmanImporter.ImportResult ir = PostmanImporter.importText(SAMPLE);
            Collection c = ir.collection;
            eq("pm collection name", "Sample", c.name);
            eq("pm collection variables", "baseUrl=https://api.example.com", kvs(c.variables));
            eq("pm top-level items", 5, c.items.size());
            Folder users = (Folder) c.items.get(0);
            eq("pm folder", "Users", users.name);
            Request list = (Request) users.items.get(0);
            eq("pm method upper-cased", "GET", list.method);
            eq("pm url", "{{baseUrl}}/users?page=1", list.url());
            eq("pm query with disabled", "page=1|size=10(off)", kvs(list.params));
            eq("pm headers", "Accept=application/json|X-Off=1(off)|Authorization=Bearer {{token}}", kvs(list.headers));
            eq("pm post-actions", "firstId=items[0].id|raw=|whole=", kvs(list.postActions));
            Request create = (Request) users.items.get(1);
            eq("pm json body mode", BodyMode.JSON, create.bodyMode);
            eq("pm json body", "{\"name\": \"{{name}}\"}", create.body);
            eq("pm basic auth", "Basic dTpw", header(create, "Authorization"));
            Request upload = (Request) c.items.get(1);
            eq("pm noauth", null, header(upload, "Authorization"));
            eq("pm skipped body", BodyMode.NONE, upload.bodyMode);
            Request plain = (Request) c.items.get(2);
            eq("pm string request", "https://example.com/plain?x=1", plain.url());
            eq("pm string request inherits auth", "Bearer {{token}}", header(plain, "Authorization"));
            Request key = (Request) c.items.get(4);
            eq("pm apikey in query", "k=v", kvs(key.params));
            eq("pm skipped report", List.of("Scripts on collections or folders",
                            "Test script code other than pm.environment.set(...) (not run)", "Body mode 'formdata'", "Auth type 'digest'"),
                    List.copyOf(ir.skipped.keySet()));

            fails("pm v1 rejected", () -> PostmanImporter.importText("{\"id\":\"x\",\"name\":\"old\",\"order\":[],\"requests\":[]}"));
            fails("pm garbage rejected", () -> PostmanImporter.importText("[1,2]"));
            PostmanImporter.ImportResult env = PostmanImporter.importText(
                    "{\"name\":\"Dev\",\"values\":[{\"key\":\"a\",\"value\":\"1\",\"enabled\":true},{\"key\":\"b\",\"value\":\"2\",\"enabled\":false}],"
                            + "\"_postman_variable_scope\":\"environment\"}");
            eq("pm environment", "Dev:a=1|b=2(off)", env.environment.name + ":" + kvs(env.environment.vars));
            eq("pm script alias forms", "id",
                    PostmanImporter.exprToPath("d.id", Set.of("d")));
            eq("pm unknown expression", null, PostmanImporter.exprToPath("someFn()", Set.of()));
        }

        void storage() throws IOException {
            Workspace w = new Workspace();
            w.collections.add(PostmanImporter.importText(SAMPLE).collection);
            Environment e = new Environment("Dev");
            e.vars.add(new KeyValue("k", "v", false));
            w.environments.add(e);
            w.activeEnvironment = "Dev";
            w.settings.timeoutSeconds = 7;
            w.settings.insecureTls = true;
            w.window = new int[] {1, 2, 800, 600, 0};
            String once = Json.write(Storage.toJson(w));
            eq("storage round trip", once, Json.write(Storage.toJson(Storage.fromJson(Json.obj(Json.parse(once))))));
            Path dir = Files.createTempDirectory("restclient-test");
            Path f = dir.resolve("sub").resolve("ws.json");
            Storage.save(w, f);
            Storage.save(w, f); // second save replaces the existing file
            Workspace back = Storage.load(f);
            eq("storage file round trip", once, Json.write(Storage.toJson(back)));
            eq("storage settings", 7, back.settings.timeoutSeconds);
            eq("storage active env", "Dev", back.activeEnv().name);
            Request copy = Storage.copy((Request) ((Folder) back.collections.get(0).items.get(0)).items.get(0));
            eq("storage copy", "List users", copy.name);
            Files.delete(f);
            Files.delete(f.getParent());
            Files.delete(dir);
            if (System.getProperty("restclient.workspace") == null)
                eq("storage default location", Storage.appDir().resolve("data").resolve("workspace.json"), Storage.defaultFile());
        }

        /**
         * Self-signed certificate for "selftest.invalid", reached as 127.0.0.1: both an untrusted
         * certificate and a hostname mismatch, the two problems curl -k ignores.
         */
        void https() throws Exception {
            Path keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
            if (!Files.exists(keytool)) { System.out.println("SKIP https: no keytool in " + keytool.getParent()); return; }
            Path dir = Files.createTempDirectory("restclient-tls");
            Path store = dir.resolve("server.p12");
            char[] pass = "changeit".toCharArray();
            try {
                Process kt = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "EC",
                        "-dname", "CN=selftest.invalid", "-validity", "2", "-storetype", "PKCS12",
                        "-keystore", store.toString(), "-storepass", "changeit")
                        .redirectErrorStream(true).start();
                String ktOut = new String(kt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (kt.waitFor() != 0) throw new IllegalStateException("keytool failed: " + ktOut);
                java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(store)) { ks.load(in, pass); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pass);
                SSLContext serverCtx = SSLContext.getInstance("TLS");
                serverCtx.init(kmf.getKeyManagers(), null, null);

                var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(serverCtx));
                server.createContext("/", ex -> {
                    byte[] out = "secure hello".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "text/plain");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                    ex.close();
                });
                server.start();
                try {
                    Request r = new Request("tls");
                    r.setUrl("https://127.0.0.1:" + server.getAddress().getPort() + "/");
                    Settings s = new Settings();
                    s.timeoutSeconds = 10;
                    try {
                        Engine.send(Engine.prepare(r, Map.of()), s).get(15, TimeUnit.SECONDS);
                        check("https strict rejects self-signed", false);
                    } catch (ExecutionException e) {
                        check("https strict failure is a TLS error", Engine.isTlsError(e));
                    }
                    s.insecureTls = true;
                    Engine.Prepared p = Engine.prepare(r, Map.of());
                    Response res = Engine.send(p, s).get(15, TimeUnit.SECONDS);
                    eq("https insecure status", 200, res.status);
                    eq("https insecure body", "secure hello", res.text);
                    check("https insecure noted in log", res.messages.stream().anyMatch(m -> m.contains("curl -k")));
                    s.insecureTls = false;
                    try {
                        Engine.send(Engine.prepare(r, Map.of()), s).get(15, TimeUnit.SECONDS);
                        check("https strict again after toggling back", false);
                    } catch (ExecutionException e) {
                        check("https strict again after toggling back", Engine.isTlsError(e));
                    }
                } finally {
                    server.stop(0);
                }
            } finally {
                Files.deleteIfExists(store);
                Files.deleteIfExists(dir);
            }
        }

        void http() throws Exception {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService pool = Executors.newCachedThreadPool();
            server.setExecutor(pool);
            server.createContext("/echo", ex -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("method", ex.getRequestMethod());
                m.put("query", ex.getRequestURI().getRawQuery());
                m.put("xtest", ex.getRequestHeaders().getFirst("X-Test"));
                m.put("contentType", ex.getRequestHeaders().getFirst("Content-Type"));
                m.put("host", ex.getRequestHeaders().getFirst("Host"));
                m.put("body", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                m.put("data", Map.of("token", "abc123"));
                byte[] out = Json.write(m).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("X-Reply", "yes");
                ex.sendResponseHeaders(201, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.createContext("/gzip", ex -> {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write("hello gzip".getBytes(StandardCharsets.UTF_8)); }
                ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                ex.getResponseHeaders().add("Content-Encoding", "gzip");
                ex.sendResponseHeaders(200, bo.size());
                ex.getResponseBody().write(bo.toByteArray());
                ex.close();
            });
            server.createContext("/fail", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
            server.createContext("/slow", ex -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            server.start();
            try {
                Settings s = new Settings();
                s.timeoutSeconds = 10;
                Collection c = new Collection("c");
                Environment env = new Environment("e");
                env.vars.add(new KeyValue("port", Integer.toString(server.getAddress().getPort()), true));
                env.vars.add(new KeyValue("bval", "x y", true));
                env.vars.add(new KeyValue("num", "5", true));

                Request r = new Request("echo");
                r.method = "POST";
                r.setUrl("http://127.0.0.1:{{port}}/echo?a=1&b={{bval}}");
                r.headers.add(new KeyValue("X-Test", "{{bval}}", true));
                r.headers.add(new KeyValue("Connection", "close", true));
                r.headers.add(new KeyValue("Host", "custom.example", true));
                r.headers.add(new KeyValue("X-Off", "1", false));
                r.bodyMode = BodyMode.JSON;
                r.body = "{\"n\": {{num}}}";
                r.postActions.add(new KeyValue("token", "data.token", true));
                r.postActions.add(new KeyValue("whole", "", true));
                r.postActions.add(new KeyValue("missing", "nope.path", true));
                Engine.Prepared p = Engine.prepare(r, Engine.variables(c, env));
                Response res = Engine.send(p, s).get(15, TimeUnit.SECONDS);
                eq("http status", 201, res.status);
                check("http response header", res.headers.stream().anyMatch(h -> h[0].equalsIgnoreCase("X-Reply") && h[1].equals("yes")));
                Map<String, Object> echoed = Json.obj(res.json());
                eq("http method", "POST", echoed.get("method"));
                eq("http query encoded", "a=1&b=x%20y", echoed.get("query"));
                eq("http header resolved", "x y", echoed.get("xtest"));
                eq("http json content type", "application/json", echoed.get("contentType"));
                eq("http host override", "custom.example", echoed.get("host"));
                eq("http body resolved", "{\"n\": 5}", echoed.get("body"));
                check("http pretty", res.pretty != null && res.pretty.contains("\n  \"method\": \"POST\""));
                check("http restricted header reported", res.messages.stream().anyMatch(m -> m.contains("'Connection'")));
                List<String> log = Engine.applyPostActions(r, res, c, env);
                eq("post token", "abc123", env.vars.stream().filter(v -> v.key.equals("token")).findFirst().map(v -> v.value).orElse(null));
                eq("post whole body", res.text, env.vars.stream().filter(v -> v.key.equals("whole")).findFirst().map(v -> v.value).orElse(null));
                check("post missing path reported", log.stream().anyMatch(m -> m.startsWith("Post-processing 'missing' failed")));

                Request g = new Request("gzip");
                g.setUrl("http://127.0.0.1:{{port}}/gzip");
                Response gz = Engine.send(Engine.prepare(g, Engine.variables(c, env)), s).get(15, TimeUnit.SECONDS);
                eq("http gzip decoded", "hello gzip", gz.text);

                Request f = new Request("fail");
                f.setUrl("http://127.0.0.1:{{port}}/fail");
                f.postActions.add(new KeyValue("x", "", true));
                Response fr = Engine.send(Engine.prepare(f, Engine.variables(c, env)), s).get(15, TimeUnit.SECONDS);
                eq("http 500", 500, fr.status);
                check("post skipped on 500", Engine.applyPostActions(f, fr, c, env).get(0).contains("not 2xx"));
                check("post on 500 left env alone", env.vars.stream().noneMatch(v -> v.key.equals("x")));

                Request slow = new Request("slow");
                slow.setUrl("http://127.0.0.1:{{port}}/slow");
                CompletableFuture<Response> cancelled = Engine.send(Engine.prepare(slow, Engine.variables(c, env)), s);
                cancelled.cancel(true);
                try { cancelled.get(); check("cancel", false); }
                catch (CancellationException e) { eq("cancel described", "Request cancelled", Engine.describe(e)); }

                s.timeoutSeconds = 1;
                try {
                    Engine.send(Engine.prepare(slow, Engine.variables(c, env)), s).get(15, TimeUnit.SECONDS);
                    check("timeout", false);
                } catch (ExecutionException e) {
                    check("timeout described", Engine.describe(e).contains("timed out"));
                }

                Request refused = new Request("refused");
                refused.setUrl("http://127.0.0.1:1/");
                try {
                    Engine.send(Engine.prepare(refused, Map.of()), s).get(15, TimeUnit.SECONDS);
                    check("connection refused", false);
                } catch (ExecutionException e) {
                    check("connection refused described", Engine.describe(e).contains("ConnectException"));
                }
            } finally {
                server.stop(0);
                pool.shutdownNow();
            }
        }
    }
}
