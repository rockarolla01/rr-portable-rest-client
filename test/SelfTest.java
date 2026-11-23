/*
 * Self-tests for PortableRestClient. They need the full JDK (they use jdk.httpserver and keytool),
 * and compile together with the app:
 *
 *   javac -Xlint:all,-serial -d build/test PortableRestClient.java test/SelfTest.java
 *   java -cp build/test SelfTest
 *
 * Exit code is non-zero when a test fails.
 */

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

// Extends the app class only so its nested types (Json, Request, Engine, ...) are in scope by
// their simple names: classes in the default package cannot be imported.
final class SelfTest extends PortableRestClient {
    public static void main(String[] args) {
        // Same as the app's main: the http tests rely on overriding Host.
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null)
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        System.exit(run());
    }

    interface Body { void run() throws Exception; }

    int passed, failed;

    static int run() {
        SelfTest t = new SelfTest();
        t.section("json", t::json);
        t.section("urls", t::urls);
        t.section("variables", t::variables);
        t.section("curl", t::curl);
        t.section("download", t::download);
        t.section("postman", t::postman);
        t.section("storage", t::storage);
        t.section("split storage", t::splitStorage);
        t.section("multipart", t::multipart);
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

        VarLookup lookup = new VarLookup(c, e);
        eq("var refs", "7-15:host|18-27:nope", VarLookup.refs("http://{{host}}/a/{{ nope}}").stream()
                .map(x -> x.start() + "-" + x.end() + ":" + x.name()).collect(Collectors.joining("|")));
        check("var exists in env", lookup.exists("host"));
        check("var exists dynamic", lookup.exists("$guid") && lookup.dynamic("$guid"));
        check("var missing", !lookup.exists("nope"));
        check("var disabled counts as missing", !new VarLookup(null, e).exists("only"));
        eq("var source env wins", "Environment 'e'", lookup.source("host"));
        eq("var source collection", "Collection 'c'", lookup.source("only"));
        eq("var source missing", null, lookup.source("nope"));
        c.variables.add(new KeyValue("nested", "{{host}}/{{gone}}", true));
        Set<String> missing = new TreeSet<>();
        eq("var resolved nested", "env.example/{{gone}}", new VarLookup(c, e).resolved("nested", missing));
        eq("var nested missing", Set.of("gone"), missing);

        Request r = new Request("p");
        r.setUrl("{{host}}/x");
        Engine.Prepared p = Engine.prepare(r, v);
        eq("default scheme", "http://env.example/x", p.uri.toString());
        r.setUrl("{{nohost}}/x");
        fails("unresolved host rejected", () -> Engine.prepare(r, v));
        r.setUrl("ftp://x/y");
        fails("ftp rejected", () -> Engine.prepare(r, v));
    }

    void download() {
        eq("dl none", "response.txt", dl(null));
        eq("dl empty", "response.txt", dl(""));
        eq("dl inline", "response.txt", dl("inline"));
        eq("dl quoted", "data.csv", dl("attachment; filename=\"data.csv\""));
        eq("dl token", "data.json", dl("attachment; filename=data.json"));
        eq("dl spaces around =", "a.zip", dl("attachment;filename = a.zip ; size=10"));
        eq("dl quoted semicolon", "a;b.txt", dl("attachment; filename=\"a;b.txt\""));
        eq("dl escaped quote", "say _hi_.txt", dl("attachment; filename=\"say \\\"hi\\\".txt\""));
        eq("dl no extension", "report.txt", dl("attachment; filename=\"report\""));
        eq("dl ext-value wins", "€ rates.csv",
                dl("attachment; filename=\"EURO rates.csv\"; filename*=UTF-8''%e2%82%ac%20rates.csv"));
        eq("dl ext-value first", "naïve.txt", dl("attachment; filename*=utf-8'en'na%C3%AFve.txt; filename=x.bin"));
        eq("dl ext-value latin1", "café.txt", dl("attachment; filename*=ISO-8859-1''caf%E9.txt"));
        eq("dl bad ext-value falls back", "x.bin", dl("attachment; filename*=garbage; filename=x.bin"));
        eq("dl case-insensitive", "Up.PNG", dl("ATTACHMENT; FILENAME=\"Up.PNG\""));
        eq("dl path dropped", "passwd.txt", dl("attachment; filename=\"../../etc/passwd\""));
        eq("dl windows path dropped", "evil.exe", dl("attachment; filename=\"C:\\\\Windows\\\\evil.exe\""));
        eq("dl illegal chars", "a_b_c.txt", dl("attachment; filename=\"a<b>c.txt\""));
        eq("dl trailing dots", "name.txt", dl("attachment; filename=\"name...\""));
        eq("dl reserved name", "_CON.txt", dl("attachment; filename=CON.txt"));
        eq("dl only dots", "response.txt", dl("attachment; filename=\"..\""));
        eq("dl dotfile", ".env.txt", dl("attachment; filename=.env"));

        // Extension when Content-Disposition doesn't give one: Content-Type, then the URL, then txt.
        String cd = "attachment; filename=\"report\"";
        eq("ext from type", "report.pdf", Engine.downloadName(cd, "application/pdf", "https://h/x"));
        eq("ext type params ignored", "response.json", Engine.downloadName(null, "Application/JSON; charset=utf-8", ""));
        eq("ext type +json", "response.json", Engine.downloadName(null, "application/problem+json", ""));
        eq("ext type +xml", "response.xml", Engine.downloadName(null, "application/atom+xml", ""));
        eq("ext jpeg", "response.jpg", Engine.downloadName(null, "image/jpeg", "https://h/photo"));
        eq("ext type beats url", "response.png", Engine.downloadName(null, "image/png", "https://h/img.jpg"));
        eq("ext url for octet-stream", "report.zip", Engine.downloadName(cd, "application/octet-stream", "https://h/f/archive.zip?v=2"));
        eq("ext url for text/plain", "response.log", Engine.downloadName(null, "text/plain; charset=utf-8", "https://h/logs/app.LOG"));
        eq("ext url without type", "response.csv", Engine.downloadName(null, "", "https://h/data.csv#top"));
        eq("ext url encoded path", "response.pdf", Engine.downloadName(null, null, "https://h/my%20file.pdf"));
        eq("ext url script ignored", "response.txt", Engine.downloadName(null, "application/octet-stream", "https://h/download.php?id=1"));
        eq("ext url dir ignored", "response.txt", Engine.downloadName(null, "", "https://h/v1.2/items"));
        eq("ext url bad", "response.txt", Engine.downloadName(null, "", "not a url"));
        eq("ext disposition kept", "a.csv", Engine.downloadName("attachment; filename=a.csv", "application/pdf", "https://h/b.zip"));
        eq("ext unknown type", "response.txt", Engine.downloadName(null, "application/x-something", "https://h/x"));
    }

    static String dl(String contentDisposition) { return Engine.downloadName(contentDisposition, "", ""); }

    void curl() {
        Request r = new Request("c");
        r.method = "POST";
        r.setUrl("https://{{host}}/a?x=1&y=2");
        r.headers.add(new KeyValue("X-Note", "it's", true));
        r.headers.add(new KeyValue("Connection", "close", true));
        r.bodyMode = BodyMode.JSON;
        r.body = "{\"a\": \"{{host}}\"}";
        Settings s = new Settings();
        s.insecureTls = true;
        String cmd = Engine.curl(Engine.prepare(r, Map.of("host", "h.example")), s);
        eq("curl command", String.join(" \\\n  ",
                "curl", "-k", "-L", "--max-time 30", "-X POST", "'https://h.example/a?x=1&y=2'",
                "-H 'X-Note: it'\\''s'", "-H 'Content-Type: application/json'",
                "--data-raw '{\"a\": \"h.example\"}'"), cmd);
        s.curlVerbose = true;
        r.method = "GET";
        r.bodyMode = BodyMode.NONE;
        r.headers.clear();
        r.setUrl("http://h.example/");
        eq("curl verbose, GET has no -X, -k only for https",
                "curl \\\n  -v \\\n  -L \\\n  --max-time 30 \\\n  http://h.example/", Engine.curl(Engine.prepare(r, Map.of()), s));
        s.curlMultiline = false;
        r.headers.add(new KeyValue("Accept", "application/json", true));
        Request f = new Request("form");
        f.method = "POST";
        f.setUrl("http://h.example/login");
        f.bodyMode = BodyMode.FORM;
        f.form.add(new KeyValue("user name", "{{u}}", true));
        f.form.add(new KeyValue("off", "x", false));
        f.form.add(new KeyValue("q", "a&b=c \u00fc", true));
        eq("form body encoded after substitution", "user+name=a%26b&q=a%26b%3Dc+%C3%BC",
                Engine.formBody(f.form, Map.of("u", "a&b"), new TreeSet<>()));
        Settings fs = new Settings();
        fs.curlMultiline = false;
        eq("curl form body", "curl -L --max-time 30 -X POST http://h.example/login "
                + "-H 'Content-Type: application/x-www-form-urlencoded' --data-raw 'user+name=a%26b&q=a%26b%3Dc+%C3%BC'",
                Engine.curl(Engine.prepare(f, Map.of("u", "a&b")), fs));
        eq("curl single line keeps a user-set Accept", "curl -v -L --max-time 30 http://h.example/ -H 'Accept: application/json'",
                Engine.curl(Engine.prepare(r, Map.of()), s));
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
                  "body": {"mode": "graphql", "graphql": {}}, "url": "{{baseUrl}}/upload"}},
                {"name": "Plain", "request": "https://example.com/plain?x=1"},
                {"name": "Digest", "request": {"method": "GET", "auth": {"type": "digest", "digest": []}, "url": "https://example.com"}},
                {"name": "Key", "request": {"method": "GET", "url": "https://example.com",
                  "auth": {"type": "apikey", "apikey": [{"key": "key", "value": "k"}, {"key": "value", "value": "v"}, {"key": "in", "value": "query"}]}}},
                {"name": "Login", "request": {"method": "POST", "url": "{{baseUrl}}/login",
                  "body": {"mode": "urlencoded", "urlencoded": [{"key": "user", "value": "{{user}}", "type": "text"},
                    {"key": "debug", "value": "1", "disabled": true}, {"key": "", "value": "blank"}]}}}
              ]
            }
            """;

    void postman() {
        PostmanImporter.ImportResult ir = PostmanImporter.importText(SAMPLE);
        Collection c = ir.collection;
        eq("pm collection name", "Sample", c.name);
        eq("pm collection variables", "baseUrl=https://api.example.com", kvs(c.variables));
        eq("pm top-level items", 6, c.items.size());
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
        Request login = (Request) c.items.get(5);
        eq("pm urlencoded mode", BodyMode.FORM, login.bodyMode);
        eq("pm urlencoded fields", "user={{user}}|debug=1(off)", kvs(login.form));
        eq("pm skipped report", List.of("Scripts on collections or folders",
                        "Test script code other than pm.environment.set(...) (not run)", "Body mode 'graphql'", "Auth type 'digest'"),
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
        w.settings.curlVerbose = true;
        w.settings.curlMultiline = false;
        w.window = new int[] {1, 2, 800, 600, 0};
        String once = Json.write(Storage.toJson(w));
        eq("storage round trip", once, Json.write(Storage.toJson(Storage.fromJson(Json.obj(Json.parse(once))))));
        Path dir = Files.createTempDirectory("portablerestclient-test");
        Path f = dir.resolve("sub").resolve("ws.json");
        Storage.save(w, f);
        Storage.save(w, f); // second save replaces the existing file
        Workspace back = Storage.load(f);
        eq("storage file round trip", once, Json.write(Storage.toJson(back)));
        eq("storage settings", 7, back.settings.timeoutSeconds);
        eq("storage active env", "Dev", back.activeEnv().name);
        Request copy = Storage.copy((Request) ((Folder) back.collections.get(0).items.get(0)).items.get(0));
        eq("storage copy", "List users", copy.name);
        Request original = (Request) ((Folder) w.collections.get(0).items.get(0)).items.get(0);
        eq("storage keeps request id", original.id, copy.id);
        check("storage copy has same content", Storage.sameContent(original, copy));
        copy.headers.add(new KeyValue("X-Draft", "1", true));
        copy.body = "changed";
        check("storage edited copy differs", !Storage.sameContent(original, copy));
        Request target = Storage.copy(original);
        List<KeyValue> headerList = target.headers;
        target.copyFrom(copy);
        check("copyFrom takes the draft", Storage.sameContent(copy, target));
        check("copyFrom keeps list identity", headerList == target.headers);
        copy.headers.get(0).value = "mutated";
        check("copyFrom copies rows", !Storage.sameContent(copy, target));
        eq("find request by id", original, w.findRequest(original.id));
        Collection dup = Storage.copy(w.collections.get(0));
        Storage.freshIds(dup);
        Request dupFirst = (Request) ((Folder) dup.items.get(0)).items.get(0);
        check("fresh ids differ", !dupFirst.id.equals(original.id));
        eq("fresh ids keep content", Json.write(Storage.item(original)).replace(original.id, ""),
                Json.write(Storage.item(dupFirst)).replace(dupFirst.id, ""));

        Workspace legacy = Storage.fromJson(Json.obj(Json.parse(
                "{\"collections\":[{\"type\":\"collection\",\"name\":\"c\",\"items\":[{\"type\":\"request\",\"name\":\"r\"}]}]}")));
        check("missing id is generated", !((Request) legacy.collections.get(0).items.get(0)).id.isEmpty());
        check("no session by default", legacy.openTabs.isEmpty() && !Storage.toJson(legacy).containsKey("session"));
        check("tree scroll defaults to left", legacy.treeScrollX == 0 && !Storage.toJson(legacy).containsKey("treeScrollX"));
        legacy.treeScrollX = 57;
        eq("tree scroll round trip", 57, Storage.fromJson(Storage.toJson(legacy)).treeScrollX);
        check("download dir defaults to none", legacy.downloadDir.isEmpty() && !Storage.toJson(legacy).containsKey("downloadDir"));
        legacy.downloadDir = "C:\\Users\\me\\Downloads\\api files";
        eq("download dir round trip", legacy.downloadDir,
                Storage.fromJson(Json.obj(Json.parse(Json.pretty(Storage.toJson(legacy))))).downloadDir);

        w.openTabs.add(new OpenTab(original.id, false, copy));
        w.openTabs.add(new OpenTab("gone", true, null));
        w.selectedTab = original.id;
        String withSession = Json.write(Storage.toJson(w));
        Workspace restored = Storage.fromJson(Json.obj(Json.parse(withSession)));
        eq("session round trip", withSession, Json.write(Storage.toJson(restored)));
        eq("session tab count", 2, restored.openTabs.size());
        eq("session selected", original.id, restored.selectedTab);
        check("session draft kept", restored.openTabs.get(0).draft() != null
                && Storage.sameContent(copy, restored.openTabs.get(0).draft()));
        check("session preview without draft", restored.openTabs.get(1).preview() && restored.openTabs.get(1).draft() == null);
        w.openTabs.clear();
        w.selectedTab = "";
        Files.delete(f);
        Files.delete(f.getParent());
        Files.delete(dir);
        String folder = System.getProperty("workspace.folder");
        try {
            System.clearProperty("workspace.folder");
            eq("storage default location", Storage.appDir().resolve("data"), Storage.workspaceFolder());
            System.setProperty("workspace.folder", "C:\\elsewhere\\ws");
            eq("workspace folder property", Path.of("C:\\elsewhere\\ws"), Storage.workspaceFolder());
            System.setProperty("workspace.folder", "..\\shared");
            eq("relative workspace folder", Storage.appDir().resolve("..\\shared").normalize(), Storage.workspaceFolder());
            eq("single file in the folder", Path.of("C:\\elsewhere\\ws\\workspace.json"),
                    new WorkspaceStore(Path.of("C:\\elsewhere\\ws")).single);
        } finally {
            if (folder == null) System.clearProperty("workspace.folder");
            else System.setProperty("workspace.folder", folder);
        }
    }

    /**
     * Self-signed certificate for "selftest.invalid", reached as 127.0.0.1: both an untrusted
     * certificate and a hostname mismatch, the two problems curl -k ignores.
     */
    void https() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        if (!Files.exists(keytool)) { System.out.println("SKIP https: no keytool in " + keytool.getParent()); return; }
        Path dir = Files.createTempDirectory("portablerestclient-tls");
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

    static String prepared(List<String[]> headers, String name) {
        for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
        return null;
    }

    /** Same content as Storage.toJson, ignoring the storage setting (it says which layout the data came from). */
    static String content(Workspace w) {
        Map<String, Object> m = Storage.toJson(w);
        Json.obj(m.get("settings")).remove("storage");
        return Json.write(m);
    }

    static Workspace sample() {
        Workspace w = new Workspace();
        w.collections.add(PostmanImporter.importText(SAMPLE).collection);
        Collection second = new Collection("Admin/Tools: \"v2\"");
        second.items.add(new Request("ping"));
        w.collections.add(second);
        w.collections.add(new Collection("admin/tools: \"V2\"")); // same file name ignoring case
        Environment dev = new Environment("Dev");
        dev.vars.add(new KeyValue("host", "localhost", true));
        w.environments.add(dev);
        w.environments.add(new Environment("CON"));
        w.activeEnvironment = "Dev";
        w.settings.timeoutSeconds = 9;
        return w;
    }

    void splitStorage() throws IOException {
        Path dir = Files.createTempDirectory("portablerestclient-split");
        try {
            WorkspaceStore store = new WorkspaceStore(dir);
            Workspace first = store.load();
            check("first run is split", first.settings.splitStorage);
            check("first run is empty", first.collections.isEmpty() && first.environments.isEmpty());

            eq("file names", List.of("Admin_Tools_ _v2_.json", "admin_tools_ _V2_ (2).json", "unnamed.json", "_CON.json"),
                    WorkspaceStore.fileNames(List.of("Admin/Tools: \"v2\"", "admin\\tools: \"V2\"", " ..", "CON")));

            Workspace w = sample();
            store.save(w, true);
            Path coll = dir.resolve("collections");
            Path envs = dir.resolve("environments");
            check("settings file", Files.exists(dir.resolve("settings.json")));
            check("no single file in split mode", !Files.exists(dir.resolve("workspace.json")));
            eq("one file per collection", 3L, countJson(coll));
            eq("one file per environment", 2L, countJson(envs));
            check("environment files", Files.exists(envs.resolve("Dev.json")) && Files.exists(envs.resolve("_CON.json")));
            check("settings.json has no collections inline",
                    !Files.readString(dir.resolve("settings.json")).contains("\"items\""));

            Workspace back = new WorkspaceStore(dir).load();
            check("split reload is split", back.settings.splitStorage);
            eq("split round trip", content(w), content(back));

            // Unchanged files are not rewritten; a changed environment is.
            FileTime old = FileTime.fromMillis(0);
            Path users = coll.resolve(WorkspaceStore.fileNames(List.of(w.collections.get(0).name)).get(0));
            Files.setLastModifiedTime(users, old);
            Files.setLastModifiedTime(envs.resolve("Dev.json"), old);
            w.environments.get(0).vars.add(new KeyValue("token", "t", true));
            store.save(w, true);
            eq("unchanged collection not rewritten", old, Files.getLastModifiedTime(users));
            check("changed environment rewritten", !old.equals(Files.getLastModifiedTime(envs.resolve("Dev.json"))));

            // A session-only save leaves the collection files alone.
            w.collections.get(1).items.add(new Request("unsaved"));
            Path admin = coll.resolve("Admin_Tools_ _v2_.json");
            Files.setLastModifiedTime(admin, old);
            store.save(w, false);
            eq("session save skips collections", old, Files.getLastModifiedTime(admin));
            store.save(w, true);
            check("data save writes the collection", Files.readString(admin).contains("unsaved"));

            // Renaming and deleting remove the old files.
            w.collections.get(1).name = "Renamed";
            w.collections.remove(2);
            store.save(w, true);
            check("renamed collection file", Files.exists(coll.resolve("Renamed.json")) && !Files.exists(admin));
            eq("deleted collection file removed", 2L, countJson(coll));
            eq("order kept from settings.json", List.of(w.collections.get(0).name, "Renamed"),
                    new WorkspaceStore(dir).load().collections.stream().map(c -> c.name).toList());

            // A broken collection file is set aside; the rest still loads.
            Files.writeString(coll.resolve("Renamed.json"), "{ broken");
            WorkspaceStore again = new WorkspaceStore(dir);
            Workspace partial = again.load();
            eq("broken collection skipped", 1, partial.collections.size());
            eq("broken collection reported", 1, again.problems.size());
            check("broken collection renamed aside", !Files.exists(coll.resolve("Renamed.json")));
            try (var s = Files.list(coll)) {
                check("aside file kept", s.anyMatch(f -> f.getFileName().toString().startsWith("Renamed.json.broken-")));
            }

            // A broken settings.json is reported with its path.
            String settingsText = Files.readString(dir.resolve("settings.json"));
            Files.writeString(dir.resolve("settings.json"), "[");
            try {
                new WorkspaceStore(dir).load();
                check("broken settings.json throws", false);
            } catch (WorkspaceStore.UnreadableFile e) {
                eq("broken settings.json named", dir.resolve("settings.json"), e.file);
            }
            Files.writeString(dir.resolve("settings.json"), settingsText);
        } finally {
            deleteTree(dir);
        }

        Path legacyDir = Files.createTempDirectory("portablerestclient-legacy");
        try {
            Path single = legacyDir.resolve("workspace.json");
            Workspace old = sample();
            old.settings.splitStorage = false;
            Storage.save(old, single);
            // Older versions wrote no storage setting.
            Files.writeString(single, Files.readString(single).replace(",\n    \"storage\": \"single\"", ""));
            check("legacy file has no storage setting", !Files.readString(single).contains("\"storage\""));
            WorkspaceStore store = new WorkspaceStore(legacyDir);
            Workspace w = store.load();
            check("existing workspace.json stays single", !w.settings.splitStorage);
            store.save(w, true);
            check("single save writes no split files",
                    !Files.exists(legacyDir.resolve("settings.json")) && !Files.exists(legacyDir.resolve("collections")));

            // Migrate single -> split: stale split files are overwritten or removed.
            Files.createDirectories(legacyDir.resolve("collections"));
            Files.writeString(legacyDir.resolve("collections").resolve("Stale.json"), "{\"type\":\"collection\",\"name\":\"Stale\"}");
            eq("existing split files listed", List.of(legacyDir.resolve("collections").resolve("Stale.json")), store.existingFiles(true));
            store.switchTo(w, true, true);
            check("migrated to split", w.settings.splitStorage);
            check("stale split file removed", !Files.exists(legacyDir.resolve("collections").resolve("Stale.json")));
            Workspace reloaded = new WorkspaceStore(legacyDir).load();
            check("split is active after migration", reloaded.settings.splitStorage);
            eq("migration keeps content", content(w), content(reloaded));

            // Split -> single without migration opens workspace.json as it was, and settings.json now points there.
            w.collections.get(0).name = "Only in split";
            w.settings.timeoutSeconds = 11;
            store.save(w, true);
            store.switchTo(w, false, false);
            check("switched to single", !w.settings.splitStorage);
            eq("single data opened", old.collections.get(0).name, w.collections.get(0).name);
            eq("settings kept across the switch", 11, w.settings.timeoutSeconds);
            check("settings.json marks single", Files.readString(legacyDir.resolve("settings.json")).contains("\"storage\": \"single\""));
            Workspace afterRestart = new WorkspaceStore(legacyDir).load();
            check("single is active after restart", !afterRestart.settings.splitStorage);
            eq("single content after restart", old.collections.get(0).name, afterRestart.collections.get(0).name);

            // ...and back to split without migration finds the split data again.
            store.switchTo(w, true, false);
            check("back to split", w.settings.splitStorage);
            eq("split data opened", "Only in split", w.collections.get(0).name);
            check("split is active again", new WorkspaceStore(legacyDir).load().settings.splitStorage);

            // Split -> single with migration overwrites workspace.json.
            store.switchTo(w, false, true);
            Workspace migrated = new WorkspaceStore(legacyDir).load();
            check("single is active after migration", !migrated.settings.splitStorage);
            eq("migrated to single", "Only in split", migrated.collections.get(0).name);
            eq("existing single file listed", List.of(single), store.existingFiles(false));
        } finally {
            deleteTree(legacyDir);
        }
    }

    static long countJson(Path folder) throws IOException {
        try (var s = Files.list(folder)) { return s.filter(p -> p.toString().endsWith(".json")).count(); }
    }

    static void deleteTree(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    void multipart() throws Exception {
        Path dir = Files.createTempDirectory("portablerestclient-multipart");
        Path file = dir.resolve("photo 1.png");
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff});
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/raw", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("X-Content-Type", ex.getRequestHeaders().getFirst("Content-Type"));
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, in.length == 0 ? -1 : in.length);
            ex.getResponseBody().write(in);
            ex.close();
        });
        server.start();
        try {
            Request r = new Request("upload");
            r.method = "POST";
            r.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/raw");
            r.bodyMode = BodyMode.MULTIPART;
            r.body = "ignored text";
            Part meta = new Part("meta", "{\"id\": {{id}}}", false, true);
            meta.headers.add(new KeyValue("Content-Type", "application/json", true));
            meta.headers.add(new KeyValue("X-Off", "1", false));
            r.parts.add(meta);
            r.parts.add(new Part("off", "x", false, false));
            Part photo = new Part("{{field}}", "{{dir}}/" + file.getFileName(), true, true);
            photo.headers.add(new KeyValue("X-Note", "a\r\nb", true));
            r.parts.add(photo);
            Map<String, String> vars = Map.of("id", "7", "field", "pic", "dir", dir.toString());

            Engine.Prepared p = Engine.prepare(r, vars);
            eq("multipart content type", "multipart/form-data; boundary=" + p.boundary, prepared(p.headers, "Content-Type"));
            eq("multipart enabled parts only", 2, p.parts.size());
            eq("multipart text part", "{\"id\": 7}", p.parts.get(0).text());
            eq("multipart file path resolved", file, p.parts.get(1).file());
            String boundary = p.boundary;
            Response res = Engine.send(p, new Settings()).get(15, TimeUnit.SECONDS);
            eq("multipart status", 200, res.status);
            eq("multipart received content type", "multipart/form-data; boundary=" + boundary, res.header("x-content-type"));
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.writeBytes(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"meta\"\r\n"
                    + "Content-Type: application/json\r\n\r\n"
                    + "{\"id\": 7}\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"pic\"; filename=\"photo 1.png\"\r\n"
                    + "X-Note: a  b\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            expected.writeBytes(Files.readAllBytes(file));
            expected.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            check("multipart body on the wire", Arrays.equals(expected.toByteArray(), res.bytes));
            check("multipart parts in the log", res.requestSummary.contains("[part 'pic': file " + file + "]"));

            // A user Content-Type: its boundary is used; without one, ours is appended.
            r.headers.add(new KeyValue("Content-Type", "multipart/form-data; boundary=\"abc\"", true));
            eq("multipart user boundary kept", "abc", Engine.prepare(r, vars).boundary);
            r.headers.get(0).value = "multipart/mixed";
            Engine.Prepared mixed = Engine.prepare(r, vars);
            eq("multipart boundary appended", "multipart/mixed; boundary=" + mixed.boundary, prepared(mixed.headers, "Content-Type"));
            Settings cs = new Settings();
            cs.curlMultiline = false;
            eq("curl multipart", "curl -L --max-time 30 -X POST " + r.url() + " -H 'Content-Type: multipart/mixed'"
                    + " -F 'meta=\"{\\\"id\\\": 7}\";headers=\"Content-Type: application/json\"'"
                    + " -F 'pic=@\"" + file.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "'\\''")
                    + "\";headers=\"X-Note: a  b\";headers=\"Content-Type: image/png\"'",
                    Engine.curl(mixed, cs));
            r.headers.clear();
            check("curl multipart leaves out the default content type",
                    !Engine.curl(Engine.prepare(r, vars), cs).contains("Content-Type: multipart"));

            photo.value = "{{dir}}/missing.bin";
            try {
                Engine.send(Engine.prepare(r, vars), new Settings()).get(15, TimeUnit.SECONDS);
                check("multipart missing file fails", false);
            } catch (ExecutionException e) {
                check("multipart missing file described", Engine.describe(e).contains("not found") && Engine.describe(e).contains("missing.bin"));
            }
            photo.value = " ";
            fails("multipart file part without a path", () -> Engine.prepare(r, vars));
            photo.value = "relative/x.bin";
            eq("multipart relative path from app folder", Storage.appDir().resolve("relative/x.bin").normalize(),
                    Engine.prepare(r, vars).parts.get(1).file());
        } finally {
            server.stop(0);
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }

        eq("disposition quoting", "a%22b%0D%0Ac", Engine.dispositionQuote("a\"b\r\nc"));
        eq("content type from extension", "image/png", Engine.contentTypeFor("a.PNG"));
        eq("content type from the app's table", "application/json", Engine.contentTypeFor("data.json"));
        eq("content type fallback", "application/octet-stream", Engine.contentTypeFor("blob.unknownext"));

        // Storage keeps the part type and headers, and a copy keeps parts as parts.
        Request q = new Request("q");
        q.bodyMode = BodyMode.MULTIPART;
        Part qp = new Part("f", "C:\\data\\a.txt", true, false);
        qp.headers.add(new KeyValue("Content-Type", "text/csv", true));
        q.parts.add(qp);
        q.parts.add(new Part("t", "v", false, true));
        Request back = Storage.copy(q);
        eq("storage multipart mode", BodyMode.MULTIPART, back.bodyMode);
        check("storage parts are parts", back.parts.get(0) instanceof Part bp && bp.file && !bp.enabled
                && bp.headers.size() == 1 && bp.headers.get(0).value.equals("text/csv")
                && back.parts.get(1) instanceof Part tp && !tp.file);
        check("storage multipart same content", Storage.sameContent(q, back));
        ((Part) back.parts.get(0)).headers.get(0).value = "text/plain";
        check("part header edit is a change", !Storage.sameContent(q, back));
        Request target = new Request("target");
        target.copyFrom(back);
        check("copyFrom copies parts deeply", target.parts.get(0) instanceof Part cp && cp.headers.get(0) != ((Part) back.parts.get(0)).headers.get(0)
                && Storage.kvs(back.parts).equals(Storage.kvs(target.parts)));

        // Table model: the blank row, type switching and undo of a header edit.
        PartModel m = new PartModel(() -> { });
        m.rows = new ArrayList<>();
        int[] picked = {-1};
        m.onBecameFile = row -> picked[0] = row;
        m.setValueAt("Text", 0, PartModel.TYPE);
        eq("choosing Text on the blank row adds nothing", 0, m.rows.size());
        m.setValueAt("doc", 0, 1);
        check("new row is a text part", m.rows.get(0) instanceof Part np && !np.file);
        m.setValueAt("File", 0, PartModel.TYPE);
        eq("switching to File asks for a file", 0, picked[0]);
        m.chooseFile(0, "x.pdf");
        eq("chosen file", "doc=x.pdf", kvs(m.rows));
        m.setHeaders(0, List.of(new KeyValue("X-A", "1", true)));
        eq("headers count cell", "1", m.getValueAt(0, PartModel.HEADERS));
        KvHistory.Snapshot undone = m.history.undo(m.rows);
        check("header edit undoes to the old part", undone.rows().get(0) instanceof Part up && up.headers.isEmpty() && up.file);

        // Postman form-data.
        Collection pc = PostmanImporter.importText("""
            {"info": {"name": "fd", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "item": [{"name": "Up", "request": {"method": "POST", "url": "https://h.example/up",
               "body": {"mode": "formdata", "formdata": [
                 {"key": "meta", "value": "{{m}}", "type": "text", "contentType": "application/json"},
                 {"key": "doc", "type": "file", "src": "/C:/files/a.pdf"},
                 {"key": "many", "type": "file", "src": ["/tmp/1.txt", "/tmp/2.txt"], "disabled": true},
                 {"key": "none", "type": "file", "src": []},
                 {"key": "", "value": "dropped"}]}}}]}
            """).collection;
        Request up = (Request) pc.items.get(0);
        eq("pm formdata mode", BodyMode.MULTIPART, up.bodyMode);
        eq("pm formdata parts", "meta={{m}}|doc=C:/files/a.pdf|many=/tmp/1.txt(off)|none=", kvs(up.parts));
        check("pm formdata types", !((Part) up.parts.get(0)).file && ((Part) up.parts.get(1)).file);
        eq("pm formdata content type", "Content-Type=application/json", kvs(((Part) up.parts.get(0)).headers));
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
        server.createContext("/download", ex -> {
            byte[] out = {0x25, 0x50, 0x44, 0x46, 0, (byte) 0xff};
            ex.getResponseHeaders().add("Content-Type", "application/pdf");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"report 2026.pdf\"");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
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

            Request fr0 = new Request("form");
            fr0.method = "POST";
            fr0.setUrl("http://127.0.0.1:{{port}}/echo");
            fr0.bodyMode = BodyMode.FORM;
            fr0.body = "ignored text";
            fr0.form.add(new KeyValue("b", "{{bval}}", true));
            fr0.form.add(new KeyValue("n", "{{num}}", true));
            Map<String, Object> formEcho = Json.obj(Engine.send(Engine.prepare(fr0, Engine.variables(c, env)), s)
                    .get(15, TimeUnit.SECONDS).json());
            eq("http form body", "b=x+y&n=5", formEcho.get("body"));
            eq("http form content type", "application/x-www-form-urlencoded", formEcho.get("contentType"));

            Request g = new Request("gzip");
            g.setUrl("http://127.0.0.1:{{port}}/gzip");
            Response gz = Engine.send(Engine.prepare(g, Engine.variables(c, env)), s).get(15, TimeUnit.SECONDS);
            eq("http gzip decoded", "hello gzip", gz.text);
            eq("no disposition gives default name", "response.txt", gz.fileName());

            Request dl = new Request("download");
            dl.setUrl("http://127.0.0.1:{{port}}/download");
            Response dr = Engine.send(Engine.prepare(dl, Engine.variables(c, env)), s).get(15, TimeUnit.SECONDS);
            eq("download name from header", "report 2026.pdf", dr.fileName());
            check("download keeps raw bytes", Arrays.equals(new byte[] {0x25, 0x50, 0x44, 0x46, 0, (byte) 0xff}, dr.bytes));

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
