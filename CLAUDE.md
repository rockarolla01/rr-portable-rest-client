# CLAUDE.md

## Project

A portable, simplified Postman-like REST client with a desktop GUI. It's written in Java and has **no third-party dependencies**.

Status: implemented (v1.0). `README.md` has the user-facing feature list.

## Hard constraints

- **JDK modules only.**
  - The app uses `java.base`, `java.desktop` (Swing) and `java.net.http` (HttpClient).
  - The packaged runtime also includes `jdk.charsets`, so responses in non-UTF charsets decode.
  - Self-tests use `jdk.httpserver`. That's fine because they run on the full JDK, but never use it in app code.
  - No Maven/Gradle, no libraries, no JUnit.
- **As few source files as possible.** Everything goes in `PortableRestClient.java` as nested classes. Don't create new source files unless asked.
- **JDK:** `C:\Soft\jdk-25.0.5.0.2+1` (Java 25 LTS). It may not be on `PATH`, so call its `bin\java.exe` / `javac.exe` directly. Java 25 language features are allowed.
- **No JavaScript.** The JDK has no JS engine, so Postman `pm.*` scripts can't run. Post-request processing is a simple rule instead (see below).

## Layout

```
PortableRestClient.java   whole application (~4,400 lines)
README.md
```

Sections inside `PortableRestClient.java`, in this order:
1. `main`:
   - Sets `jdk.httpclient.allowRestrictedHeaders=host`. It must happen before any `java.net.http` class loads.
   - Then launches the GUI, or runs `--selftest`.
2. `Json`: recursive-descent parser, compact writer, pretty-printer, `at(root, path)` lookup, and loose accessors (`obj`/`arr`/`str`/`bool`/`integer`).
   - Objects are `LinkedHashMap`, so key order is kept.
   - Numbers are `Json.Num` (the original text), so values are never rounded.
3. Model: `KeyValue(key, value, enabled)`, `BodyMode`, `Item` → `Folder` → `Collection`, `Request`, `Environment`, `Settings`, `Workspace`.
   - `Request` stores `baseUrl` (no query) plus a `params` list. `url()` joins them; `setUrl(text)` splits them.
   - `Request.id` is a UUID, persisted and generated when missing (older files), so session tabs can find their request. `copyFrom` copies every editable field except name and id, mutating the lists in place.
   - `OpenTab(requestId, preview, draft)` and `Workspace.openTabs` / `selectedTab`: the tab session. `draft` is null when the tab had no unsaved changes.
   - Post-actions are a `List<KeyValue>`: key = variable name, value = JSON path (empty = whole body).
   - `BodyMode.FORM` uses `Request.form` (a `List<KeyValue>`). It's kept separate from `body`, so switching modes loses nothing.
4. `Urls`: query parse/join and `encode`, which percent-encodes only illegal characters and keeps existing `%XX` escapes.
5. `Storage`: the workspace JSON format.
   - The default file is `<appDir>\data\workspace.json`; override it with `-Dportablerestclient.workspace=…`.
   - `appDir()` is the folder the app runs from:
     - jpackage exe: the folder of `jpackage.app-path`.
     - jar: the jar's folder.
     - `java PortableRestClient.java`: the source file's folder.
     - compiled classes: the classes directory.
   - `data/` is git-ignored.
   - Saves write a temp file and then do an atomic rename.
   - `Storage.copy` deep-copies an item by serializing it and reading it back. It keeps request ids; `freshIds` gives a duplicate new ones.
   - `sameContent(a, b)` compares the serialized forms; it is the "unsaved changes" test.
   - The tab session goes under a `"session"` key (`selected`, `tabs[{id, preview, draft?}]`), written only when tabs are open.
6. `PostmanImporter`: Postman collection v2.0/v2.1 and environment exports. Returns an `ImportResult` that includes the skipped-features report.
7. `Response`, `Engine`: variable resolution, `prepare` (builds the URI and headers), async `send` (cancellable), response decoding, `applyPostActions`.
8. UI:
   - `KvTable`/`KvModel`: reusable key/value table whose last row is a blank row for adding entries.
   - `Ui`: the frame, tree, dialogs, import, and send flow.
   - `Ui.RequestTab`: one open request, with its own `RequestEditor`, plus `Ui.StripMouse` (clicks, drag reorder).
   - `Ui.RequestEditor` (one per tab) and `Ui.ResponseView` (shared; shows the selected tab's last response).
9. `SelfTest`: plain assertions, plus an end-to-end HTTP test against a local `HttpServer`.

## Commands

```powershell
$J = "C:\Soft\jdk-25.0.5.0.2+1\bin"
& "$J\java.exe" PortableRestClient.java              # run from source, no build step
& "$J\java.exe" PortableRestClient.java --selftest   # run self-tests (exit code != 0 on failure)```

- After any change to `Json`, `Urls`, `Storage`, `PostmanImporter` or `Engine`, run `--selftest`.
- Add a self-test case when you fix a bug in those sections.
- Compile with `-Xlint:all,-serial`; it should produce no warnings.
- **GUI testing:** don't drive the user's real desktop (focus stealing, screen capture). Use a harness instead:
  - Construct `PortableRestClient.Ui` with a scratch workspace.
  - Call `frame.setFocusableWindowState(false)`.
  - Paint the root pane into a `BufferedImage`.

## Behaviour rules

- **Variable resolution:** active environment first, then collection variables.
  - Applies to the URL, params, headers and body.
  - Values may themselves contain variables (resolved up to 10 levels deep).
  - Built-in dynamic variables: `$guid`, `$randomUUID`, `$timestamp`, `$isoTimestamp`, `$randomInt`.
  - Unresolved `{{x}}` is sent as written and reported in the response Log tab.
- **Variable highlighting:** `VarLookup` (non-UI, self-tested) finds `{{name}}` references and reports, for each one, whether it exists, its source and its resolved value. It uses the same collection and environment as a send.
  - Orange = defined (including dynamic variables), red = undefined. It applies to the URL field, the body, and the key/value columns of Params, Headers and Form.
  - Text fields use `Highlighter` painters (`Ui.highlightVars`). Table cells use an HTML renderer (`Ui.varHtml`) and a highlighted `JTextField` editor (`KvTable.highlightVariables`).
  - Hover tips: `Ui.varTipAt` reads only about 256 characters around the mouse. A table cell lists every variable in it.
  - Refresh is debounced by `highlightTimer` (150 ms), restarted from `changed()`, so typing, environment switches and variable edits all re-colour. Bodies over 2M characters aren't scanned.
- **Line numbers:** `LineNumbers.scroll(textArea)` wraps the request and response bodies. It's the scroll pane's row header, and it assumes the text area doesn't wrap lines.
  - It paints only the visible lines. The width follows the digit count (minimum 2).
  - Current line: a band in the text area, drawn by a plain (non-layered) highlight painter so it sits under the selection and the variable highlights, plus a bold number in the gutter. It's hidden while the text area is disabled.
- **Find (Ctrl+F):** `FindBar.wrap(textArea, view)` puts a hidden find bar above the request and response bodies.
  - It does a literal search, case-insensitive unless Match case is ticked. Highlights are capped at `MAX_MATCHES` (10,000), and the count then shows `+`.
  - The current match has its own painter (yellow with an outline), because the text area's selection is hidden while the find field has focus. It's also selected, so it stays selected after Esc.
  - Typing a query jumps from where the search started (`anchor`). A text change only re-highlights (debounced 200 ms) and never moves the caret, so you can keep typing in the body while the bar is open.
- **Table undo (Ctrl+Z / Ctrl+Y):** `KvHistory` keeps whole-table snapshots (max 200) per `KvModel`.
  - `KvModel.setValueAt` (skipping no-op edits) and `KvTable.removeSelected` record the rows as they were before the change.
  - Restoring mutates `rows` in place and calls `onChange`, so undo in Params also rewrites the URL.
  - The keys are bound `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT`. While a cell is being edited, Ctrl+Z cancels the edit instead.
  - `setRows` (opening a tab, reverting) and `refresh` (rows changed from outside, e.g. typing in the URL) clear the history, because older snapshots no longer apply.
- **URL undo:** `RequestEditor.urlUndo`, cleared in `load` (which runs when a tab opens and on Revert, not on save).
  - A URL rewrite from `paramsChanged` is recorded too, wrapped in one `CompoundEdit` (`urlSync`). Document undo edits are position-based, so skipping a programmatic change would break later undos.
  - Undoing in the URL goes through `urlChanged`, which updates the params and so clears the Params table history.
- **Request tabs:**
  - `Ui.strip` is a `JTabbedPane` (`SCROLL_TAB_LAYOUT`) holding only empty placeholders, so the ▾ list button can sit beside it. The editors are cards in `Ui.editors`. `openTabs.get(i)` is always tab `i`.
  - Each `RequestTab` has `saved` (the workspace object, the tree node's user object) and `draft` (`Storage.copy`). The editor only ever edits the draft. `results` and `inFlight` are keyed by `saved`.
  - Preview: a tree selection or single click calls `openTab(node, true)`, which replaces the existing preview tab when it isn't dirty or sending. Editing (`draftEdited`), sending, double-clicking the request or its tab, Enter in the tree, or "Keep Open" pins it.
  - The `switching` flag guards programmatic strip changes and tree selections, so tab ↔ tree sync doesn't echo.
  - Dirty state comes from `Storage.sameContent(draft, saved)`, checked in `refreshCurrent` (`highlightTimer`, 150 ms). It shows as ● on the tab (the close button shows × on hover), in the tree, and as the editor's enabled Save button.
  - Save (`saveTab`, Ctrl+S; `saveAll`, Ctrl+Shift+S) does `saved.copyFrom(draft)` and then writes the workspace. A workspace save writes only saved requests, plus the session via `syncSession`.
  - Saved immediately, not buffered: rename (it also renames the draft), move, duplicate (copies the saved version, with `freshIds`), delete (closes affected tabs, warns when they're dirty), environments, variables, settings and post-action results.
  - Closing a dirty tab asks Save / Don't Save / Cancel. Exit never asks: drafts are persisted in the session and restored by `restoreTabs`. Tabs whose request is gone are dropped.
  - Closing a tab cancels its send. `finished` ignores a future that is no longer in `inFlight`.
  - Ctrl+Tab and Ctrl+PgUp/PgDn go through a `KeyEventDispatcher` (`tabSwitchKey`), because Ctrl+Tab is a focus-traversal key and the inner tabbed panes and tables bind Ctrl+PgUp/PgDn. Ctrl+S, Ctrl+Shift+S and Ctrl+W are root-pane bindings.
  - Drag reorder: `StripMouse` listens on the strip, its internal tab panel and every tab header (a header with a tooltip swallows its clicks). The drop marker is painted in the strip's `paint` override. Auto-scroll at the edges and wheel scrolling move the strip's `JViewport` (`scrollStrip`).
- **Params ↔ URL sync** works in both directions, with the `syncing` guard flag stopping update loops.
  - Editing the URL replaces the enabled params and keeps the disabled ones.
  - Mutate `params` in place: the table holds a reference to the list.
- **Query encoding:** substitute variables first, then call `Urls.encode`. Never re-encode a URL that's already encoded.
- **Default headers** are added unless the user set them:
  - JSON body → `Content-Type: application/json`.
  - Text body → `text/plain; charset=UTF-8`.
  - Form body → `application/x-www-form-urlencoded`.
  - `User-Agent: PortableRestClient/1.0` and `Accept: */*`.
- **Form body (`x-www-form-urlencoded`):** `Engine.formBody` substitutes variables in each enabled field first, then encodes the key and value with `URLEncoder` (UTF-8, space → `+`). That way a resolved `&` or `=` can't split a field.
- **Response display:**
  - Pretty-print when the content type is JSON or the body starts with `{`/`[` and parses. This is computed off the UI thread in `Response.analyze()`.
  - The charset comes from `Content-Type`; gzip/deflate bodies are decoded.
  - Binary bodies show their size only; display is capped at 10 MB.
- **Post-processing:** only on a 2xx status. Values go to the active environment, or to the collection variables when there is none.
  - Strings are stored raw; other JSON values are stored as compact JSON.
  - Failures (path not found, not JSON) go to the Log tab and never throw.
- **Networking:**
  - Never call the network on the Swing UI thread; update the UI through `SwingUtilities.invokeLater`.
  - Model mutations (post-actions) happen on the UI thread. Post-actions use the draft that was sent.
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
- **curl tab:** `Engine.curl(Prepared, Settings)` builds a POSIX-shell command from the same `prepare` output that `send` uses.
  - Settings map to `-k` (https only), `-L`, `--http2` and `--max-time`. Restricted headers are left out, as in `send`.
  - The default `User-Agent` and `Accept` (`Prepared.implicit`) are left out too, since curl sends its own; they stay in what `send` sends. A user-set value is always shown.
  - `Settings.curlVerbose` (`-v`) and `Settings.curlMultiline` (` \` continuations vs. one line; default on) are global, persisted flags.
  - The text is rebuilt only while the tab is selected, so `{{$guid}}` and similar don't change on every keystroke.
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
  - `body.mode=urlencoded` → form body, from `urlencoded[]` with `disabled`. Entries without a key are dropped.
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
  - Other body modes (formdata, file, graphql) and other auth types.
  - Pre-request scripts, scripts on collections or folders, other test code, and path variables.
  - Never fail an import over an unsupported feature.
  - Postman v1 collections are rejected with a clear message.

## Code style

- Keep it plain and readable: small methods, no reflection, no clever generics.
- Use records for immutable values and simple mutable classes for UI-edited model objects.
- Comments explain *why*, not *what*.
