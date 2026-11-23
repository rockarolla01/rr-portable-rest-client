# Portable REST Client

**#vibeCoded**

A small, portable REST client in the spirit of Postman. Key features:
- All the source code is located in a single file, `PortableRestClient.java`, with no third-party dependencies.
- Fully portable: all user data (collections and environments) is stored in a local folder next to the source file.
- Supports basic REST client features:
  - HTTP requests.
  - Collections of requests and environments.
  - Minimalistic request post-processing (namely, "get a JSON response field and store it in an environment variable"). No scripting is supported.
  - curl command generation.
  - Import of Postman collections and environments.

It requires only a JDK to run (which can be portable too, e.g. OpenJDK; tested with JDK 21 and 25). You don't need to compile `PortableRestClient.java`; you can run the source file directly.

For example:
```
cd C:\portable-rest-client-location

C:\my-openjdk-location\bin\java.exe PortableRestClient.java
```

Below is a detailed, AI-generated overview.

---

## Features

- Collections with nested folders, plus rename, duplicate, reorder and delete (right-click the tree).
- Requests open in tabs:
  - A single click in the tree opens a *preview* tab (italic title), and the next click reuses it. Double-click the request or its tab, edit it, or send it to keep the tab open.
  - Each tab has its own undo history, caret, scroll position, find bar and sub-tab, plus its own last response.
  - Drag tabs to reorder them. When there are more tabs than fit, the row scrolls with the ‹ › arrows or the mouse wheel. The **▾** button lists every open tab, with a filter field.
  - Right-click a tab for Save, Revert Changes, Close, Close Others, Close to the Right, Close Saved and Reveal in Tree. Middle-click closes a tab.
- Edits stay in the tab until you save them with **Ctrl+S** or the **Save** button.
  - A ● on the tab and in the tree marks unsaved changes. Undoing back to the saved state clears it.
  - **Send** and the curl tab use what you see, saved or not.
  - Closing a tab with unsaved changes asks whether to save them. Exiting doesn't ask: open tabs and their unsaved drafts are restored the next time you start the app.
  - Renaming, moving, duplicating (which copies the saved version) and deleting in the tree are saved immediately, as are changes to environments, variables and settings.
- Environments and collection variables. `{{var}}` placeholders work in the URL, params, headers and body.
  - The active environment wins over collection variables.
  - In the URL, body, params, headers and form fields, `{{var}}` references are highlighted: orange if the variable is defined, red if not. Hover one to see its value and where it comes from.
  - A few dynamic variables are built in: `{{$guid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`.
- Query parameters stay in sync with the URL in both directions. Disabled params are kept but not sent.
- Request headers, each with an enabled checkbox.
- Request body: none, plain text, JSON (with a "Format JSON" button), or `x-www-form-urlencoded` form fields (a key/value table with enabled checkboxes; keys and values are encoded for you).
- **Multipart (form-data)** bodies for file uploads. Each part has a name, a **Text** / **File** type and a value; switching a part to File opens a file chooser, and **Browse…** in the cell changes the file.
  - The **…** button on a part edits headers sent with that part only (for example `Content-Type: application/json` on a text part); it shows how many are set.
  - `Content-Disposition` is generated, and a file part gets a `Content-Type` guessed from its extension, unless you set them yourself.
  - Files are read while sending, not when saved. A relative path is taken from the app folder (files you pick inside it are stored that way), so a portable copy can carry its upload files.
  - Variables work in part names, text values, file paths and part headers.
- The request and response body editors have line numbers, and the line with the cursor is highlighted.
- **Ctrl+F** in either body opens a find bar. All matches are highlighted, with a count such as "3 of 12", and the search isn't case-sensitive unless you tick **Match case**.
  - **Enter** / **Shift+Enter** (or **F3** / **Shift+F3**) step forward and back, wrapping around.
  - Selected text becomes the search term.
  - **Esc** closes the bar and leaves the current match selected.
- Response:
  - Status, time, size and HTTP version.
  - JSON bodies are pretty-printed; gzip/deflate bodies are decoded.
  - Response headers, plus a log tab showing the request as it was actually sent and any warnings.
  - **Downloads:** the **▾** next to **Send** offers **Send and Download…**, which saves the body to a file instead of showing it. **Save to File…** saves a response you already have.
    - The save dialog opens in the folder of the last saved response, even after a restart.
    - The suggested file name comes from the `Content-Disposition` header (`filename*` is preferred over `filename`).
    - Without one, the name is `response`.
    - A name without an extension gets one from the `Content-Type` (e.g. `application/pdf` → `.pdf`). For generic types such as `application/octet-stream` or `text/plain`, the extension in the URL (`…/archive.zip`) is used instead. If neither helps, it's `.txt`.
- Post-processing: after a 2xx response, store the whole body, or a value at a JSON path such as `data.items[0].id`, in an environment variable.
- Postman import of collections (v2.0 and v2.1) and environments. Unsupported parts are skipped and listed in a report.
  - Raw (text/JSON), `urlencoded` and `formdata` (multipart) bodies are imported. Check the file paths of file parts afterwards: Postman may store them relative to its own working folder.
  - Bearer, basic and API-key auth are converted into headers or params.
  - Simple `pm.environment.set(..., pm.response.json()...)` test scripts become post-processing rules.
- **Ignore SSL errors**, like `curl -k`, from a checkbox in the toolbar (also in Settings).
  - It skips certificate checks, so self-signed, expired and untrusted certificates are accepted, and it skips the hostname check.
  - It applies to all requests and is saved with the workspace.
  - The response Log tab notes when it was in effect.
  - When it's off and a request fails with an SSL error, the error message suggests turning it on.
- A **curl** tab on each request shows the equivalent `curl` command (POSIX shell quoting), with variables resolved and a Copy button.
  - The **Verbose (-v)** and **Multi-line** checkboxes apply to all requests and are saved with the workspace.
- Settings: timeout, redirects, HTTP/2, and workspace storage (one file or multiple files).
- Everything else auto-saves to the `data` folder next to the source file (see [Data location](#data-location)).

## Run

```powershell
$J = "C:\Soft\jdk-25.0.5.0.2+1\bin"
& "$J\java.exe" PortableRestClient.java              # run from source, no build step
```

Tests (`test\SelfTest.java`) compile together with the app; the exit code is non-zero on failure:

```powershell
powershell -ExecutionPolicy Bypass -File test\run.ps1   # optional: -Jdk <jdk folder>
```

or by hand:

```powershell
& "$J\javac.exe" -d build\test PortableRestClient.java test\SelfTest.java
& "$J\java.exe" -cp build\test SelfTest
```

## Data location

Collections, environments, settings and the open tabs (with their unsaved drafts) are saved in the `data` folder (git-ignored) next to the source file. **Settings → Workspace storage** picks the layout:

- **Multiple files** (the default for a new workspace):
  - `data\settings.json`: settings, window and tree state, the open tabs, and the order of the other files.
  - `data\collections\<name>.json`: one file per collection.
  - `data\environments\<name>.json`: one file per environment.

  Only files whose content changed are rewritten. Switching tabs or typing in an unsaved draft writes `settings.json` alone.
- **One file**: everything in `data\workspace.json`. A workspace from an older version keeps using it until you switch.

When you switch layouts, the **Migrate workspace** checkbox becomes available:

- Checked: the current workspace is written in the new layout. You're warned first, because existing files in that layout are overwritten. In the multiple-files layout, collection and environment files that aren't part of the workspace are deleted.
- Unchecked: the app opens whatever is already stored in the new layout, or an empty workspace. Settings stay as they are. The old layout's files are kept, so switching back brings them back.

A file that can't be read is renamed to `<name>.broken-<time>`, and you're told about it.

To use a different folder, add `-Dworkspace.folder=C:\path\to\folder` to the `java` command, before the source file or class name. A relative path is resolved against the app's folder. Both layouts live in that folder: the one-file layout reads and writes `workspace.json` there.

## Shortcuts

- **Ctrl+Enter**, or **Enter** in the URL field: send. While a request is running, Ctrl+Enter or the button cancels it.
- **Ctrl+S**: save the current tab. **Ctrl+Shift+S**: save all tabs.
- **Ctrl+W**: close the current tab.
- **Ctrl+Tab** / **Ctrl+Shift+Tab**, or **Ctrl+PgDn** / **Ctrl+PgUp**: next / previous tab.
- **Enter** on a request in the tree: open it in a tab that stays open.
- **F2** / **Delete** on the tree: rename or delete.
- **Delete** in a table: remove the selected rows.
- **Ctrl+Z** / **Ctrl+Y** in the body editor or the URL field: undo / redo, separately in each tab. A URL change made from the Params table undoes as one step.
- **Ctrl+Z** / **Ctrl+Y** in a table (Params, Headers, Form, Multipart, Post-processing, variables): undo / redo cell edits, added rows and removed rows. While a cell is being edited, Ctrl+Z first discards the typed text. Saving keeps the history; closing the tab discards it.
- **Ctrl+F** in the request or response body: find. **Enter** / **Shift+Enter** or **F3** / **Shift+F3** for the next / previous match; **Esc** closes the find bar.

## Not supported

Postman scripts (other than the simple variable-setting pattern), binary file bodies, other auth types (OAuth, digest, …), cookie management, WebSocket/GraphQL, and export back to Postman.
