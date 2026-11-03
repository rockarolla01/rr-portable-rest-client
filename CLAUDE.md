# CLAUDE.md

## Project

A standalone, simplified Postman-like REST client with a desktop GUI. It's written in Java and has **no third-party dependencies**.

Status: implemented (v1.0). `README.md` has the user-facing feature list.

## Hard constraints

- **JDK modules only.**
  - The app uses `java.base`, `java.desktop` (Swing) and `java.net.http` (HttpClient).
  - The packaged runtime also includes `jdk.charsets`, so responses in non-UTF charsets decode.
  - Self-tests use `jdk.httpserver`. That's fine because they run on the full JDK, but never use it in app code.
  - No Maven/Gradle, no libraries, no JUnit.
- **As few source files as possible.** Everything goes in `StandaloneRestClient.java` as nested classes. Don't create new source files unless asked.
- **JDK:** `C:\Soft\jdk-25.0.5.0.2+1` (Java 25 LTS). It may not be on `PATH`, so call its `bin\java.exe` / `javac.exe` directly. Java 25 language features are allowed.
- **No JavaScript.** The JDK has no JS engine, so Postman `pm.*` scripts can't run. Post-request processing is a simple rule instead (see below).

## Layout

```
StandaloneRestClient.java   whole application (~2,700 lines)
README.md
```

Sections inside `StandaloneRestClient.java`, in this order:
1. `main`:
   - Sets `jdk.httpclient.allowRestrictedHeaders=host`. It must happen before any `java.net.http` class loads.
   - Then launches the GUI, or runs `--selftest`.
2. `Json`: recursive-descent parser, compact writer, pretty-printer, `at(root, path)` lookup, and loose accessors (`obj`/`arr`/`str`/`bool`/`integer`).
   - Objects are `LinkedHashMap`, so key order is kept.
   - Numbers are `Json.Num` (the original text), so values are never rounded.
3. Model: `KeyValue(key, value, enabled)`, `BodyMode`, `Item` → `Folder` → `Collection`, `Request`, `Environment`, `Settings`, `Workspace`.
   - `Request` stores `baseUrl` (no query) plus a `params` list. `url()` joins them; `setUrl(text)` splits them.
   - Post-actions are a `List<KeyValue>`: key = variable name, value = JSON path (empty = whole body).
4. `Urls`: query parse/join and `encode`, which percent-encodes only illegal characters and keeps existing `%XX` escapes.
5. `Storage`: the workspace JSON format.
   - The default file is `<appDir>\data\workspace.json`; override it with `-Drestclient.workspace=…`.
   - `appDir()` is the folder the app runs from:
     - jpackage exe: the folder of `jpackage.app-path`.
     - jar: the jar's folder.
     - `java StandaloneRestClient.java`: the source file's folder.
     - compiled classes: the classes directory.
   - `data/` is git-ignored.
   - Saves write a temp file and then do an atomic rename.
   - `Storage.copy` deep-copies an item by serializing it and reading it back.
6. `PostmanImporter`: Postman collection v2.0/v2.1 and environment exports. Returns an `ImportResult` that includes the skipped-features report.
7. `Response`, `Engine`: variable resolution, `prepare` (builds the URI and headers), async `send` (cancellable), response decoding, `applyPostActions`.
8. UI:
   - `KvTable`/`KvModel`: reusable key/value table whose last row is a blank row for adding entries.
   - `Ui`: the frame, tree, dialogs, import, and send flow.
   - `Ui.RequestEditor` and `Ui.ResponseView`.
9. `SelfTest`: plain assertions, plus an end-to-end HTTP test against a local `HttpServer`.

## Commands

```powershell
$J = "C:\Soft\jdk-25.0.5.0.2+1\bin"
& "$J\java.exe" StandaloneRestClient.java              # run from source, no build step
& "$J\java.exe" StandaloneRestClient.java --selftest   # run self-tests (exit code != 0 on failure)```

- After any change to `Json`, `Urls`, `Storage`, `PostmanImporter` or `Engine`, run `--selftest`.
- Add a self-test case when you fix a bug in those sections.
- Compile with `-Xlint:all,-serial`; it should produce no warnings.
- **GUI testing:** don't drive the user's real desktop (focus stealing, screen capture). Use a harness instead:
  - Construct `StandaloneRestClient.Ui` with a scratch workspace.
  - Call `frame.setFocusableWindowState(false)`.
  - Paint the root pane into a `BufferedImage`.

## Behaviour rules

- **Variable resolution:** active environment first, then collection variables.
  - Applies to the URL, params, headers and body.
  - Values may themselves contain variables (resolved up to 10 levels deep).
  - Built-in dynamic variables: `$guid`, `$randomUUID`, `$timestamp`, `$isoTimestamp`, `$randomInt`.
  - Unresolved `{{x}}` is sent as written and reported in the response Log tab.
- **Params ↔ URL sync** works in both directions, with the `syncing` guard flag stopping update loops.
  - Editing the URL replaces the enabled params and keeps the disabled ones.
  - Mutate `params` in place: the table holds a reference to the list.
- **Query encoding:** substitute variables first, then call `Urls.encode`. Never re-encode a URL that's already encoded.
- **Default headers** are added unless the user set them:
  - JSON body → `Content-Type: application/json`.
  - Text body → `text/plain; charset=UTF-8`.
  - `User-Agent: RestClient/1.0` and `Accept: */*`.
- **Response display:**
  - Pretty-print when the content type is JSON or the body starts with `{`/`[` and parses. This is computed off the UI thread in `Response.analyze()`.
  - The charset comes from `Content-Type`; gzip/deflate bodies are decoded.
  - Binary bodies show their size only; display is capped at 10 MB.
- **Post-processing:** only on a 2xx status. Values go to the active environment, or to the collection variables when there is none.
  - Strings are stored raw; other JSON values are stored as compact JSON.
  - Failures (path not found, not JSON) go to the Log tab and never throw.
- **Networking:**
  - Never call the network on the Swing UI thread; update the UI through `SwingUtilities.invokeLater`.
  - Model mutations (post-actions) happen on the UI thread.
  - Cancelling the future returned by `Engine.send` aborts the HTTP exchange.
  - Defaults to HTTP/1.1; HTTP/2 is a setting.
  - The `HttpClient` is cached and rebuilt only when settings change.
  - **Ignore SSL errors** (`Settings.insecureTls`, like `curl -k`):
    - A toolbar checkbox, kept in sync with the Settings dialog.
    - Uses an `X509ExtendedTrustManager` that accepts everything. Because it's the *extended* interface, the JDK also skips the hostname check, so no `jdk.internal.httpclient.disableHostnameVerification` is needed.
    - The setting is part of the cached client's key, so toggling it takes effect on the next request.
    - HTTPS requests sent with it on get a note in the Log tab.
    - When it's off, a TLS failure (`Engine.isTlsError`) gets a hint to turn it on.
    - The `https` self-test covers it with a self-signed certificate plus a hostname mismatch, generated by `keytool`.
- **Restricted headers:**
  - `Host` can be overridden, via the property set in `main`.
  - `Connection`, `Content-Length`, `Expect` and `Upgrade` are skipped, with a note in the log.

## Postman import

- **Supported:**
  - Nested `item[]` (folders and requests); `request` given as a plain string.
  - The Postman API wrapper `{"collection": {...}}`.
  - `url` as a string or an object: uses `raw` for the base, and `query[]` with `disabled`, falling back to parsing the query from `raw`.
  - `header[]` with `disabled`, or v2.0 `"K: V"` header strings.
  - `body.mode=raw`: JSON when `options.raw.language=json`, or when there is no language and the text starts with `{`/`[`; plain text otherwise.
  - Collection `variable[]`.
  - Environment exports `{name, values:[{key,value,enabled}]}`. Imported environment names are made unique.
- **Auth:**
  - Inherited down the tree; `inherit` or a missing auth uses the parent's.
  - Bearer → `Authorization: Bearer …`.
  - Basic → `Authorization: Basic base64`. Skipped if the username or password uses `{{vars}}`, because the encoding is done at import time.
  - API key → a header or a query param.
  - `noauth` → nothing.
- **Scripts:** never run.
  - Test-script lines matching `pm.environment|collectionVariables|globals|variables.set(...)`, or the legacy `postman.setEnvironmentVariable(...)`, with values from these forms become post-actions:
    - `pm.response.json()…` or `pm.response.text()`
    - `responseBody`
    - `JSON.stringify(...)`
    - an alias created by `var x = pm.response.json()`
  - Anything else is reported.
- **Ignored and reported:**
  - Other body modes (formdata, urlencoded, file, graphql) and other auth types.
  - Pre-request scripts, scripts on collections or folders, other test code, and path variables.
  - Never fail an import over an unsupported feature.
  - Postman v1 collections are rejected with a clear message.

## Code style

- Keep it plain and readable: small methods, no reflection, no clever generics.
- Use records for immutable values and simple mutable classes for UI-edited model objects.
- Comments explain *why*, not *what*.
