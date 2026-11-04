# Standalone REST Client

A small, standalone REST client in the spirit of Postman. It's a single Java source file with no third-party libraries; it needs only JDK 25.

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
  - Rename, move, duplicate (which copies the saved version) and delete in the tree, environments, variables and settings still save at once.
- Environments, and collection variables. `{{var}}` placeholders work in the URL, params, headers and body.
  - The active environment wins over collection variables.
  - In the URL, body, params, headers and form fields, `{{var}}` references are highlighted: orange if the variable is defined, red if not. Hover one to see its value and where it comes from.
  - A few dynamic variables are built in: `{{$guid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`.
- Query parameters stay in sync with the URL in both directions. Disabled params are kept but not sent.
- Request headers, each with an enabled checkbox.
- Request body: none, plain text, JSON (with a "Format JSON" button), or `x-www-form-urlencoded` form fields (a key/value table with enabled checkboxes; keys and values are encoded for you).
- The request and response body editors have line numbers, and the line with the cursor is highlighted.
- **Ctrl+F** in either body opens a find bar. All matches are highlighted, with a count such as "3 of 12", and the search isn't case-sensitive unless you tick **Match case**.
  - **Enter** / **Shift+Enter** (or **F3** / **Shift+F3**) step forward and back, wrapping around.
  - Selected text becomes the search term.
  - **Esc** closes the bar and leaves the current match selected.
- Response:
  - Status, time, size and HTTP version.
  - JSON bodies are pretty-printed; gzip/deflate bodies are decoded.
  - Response headers, plus a log tab showing the request as it was actually sent and any warnings.
- Post-processing: after a 2xx response, store the whole body, or a value at a JSON path such as `data.items[0].id`, in an environment variable.
- Postman import of collections (v2.0 and v2.1) and environments. Unsupported parts are skipped and listed in a report.
  - Raw (text/JSON) and `urlencoded` bodies are imported.
  - Bearer, basic and API-key auth are converted into headers or params.
  - Simple `pm.environment.set(..., pm.response.json()...)` test scripts become post-processing rules.
- **Ignore SSL errors**, like `curl -k`, from a checkbox in the toolbar (also in Settings).
  - It skips certificate checks, so self-signed, expired and untrusted certificates are accepted, and it skips the hostname check.
  - It applies to all requests and is saved with the workspace.
  - The response Log tab notes when it was in effect.
  - When it's off and a request fails with an SSL error, the error message suggests turning it on.
- A **curl** tab on each request shows the equivalent `curl` command (POSIX shell quoting), with variables resolved and a Copy button.
  - The **Verbose (-v)** and **Multi-line** checkboxes apply to all requests and are saved with the workspace.
- Settings: timeout, redirects, HTTP/2.
- Everything else auto-saves to `data\workspace.json` next to the source file (see [Data location](#data-location)).

## Run

```powershell
$J = "C:\Soft\jdk-25.0.5.0.2+1\bin"
& "$J\java.exe" StandaloneRestClient.java              # run from source, no build step
& "$J\java.exe" StandaloneRestClient.java --selftest   # built-in tests
```

## Data location

Collections, environments, settings and the open tabs (with their unsaved drafts) are saved in one file, `standalone-rest-client\data\workspace.json` (git-ignored), next to the source file.

To use a different file, add `-Drestclient.workspace=C:\path\to\workspace.json` to the `java` command.

## Shortcuts

- **Ctrl+Enter**, or **Enter** in the URL field: send. While a request is running, Ctrl+Enter or the button cancels it.
- **Ctrl+S**: save the current tab. **Ctrl+Shift+S**: save all tabs.
- **Ctrl+W**: close the current tab.
- **Ctrl+Tab** / **Ctrl+Shift+Tab**, or **Ctrl+PgDn** / **Ctrl+PgUp**: next / previous tab.
- **Enter** on a request in the tree: open it in a tab that stays open.
- **F2** / **Delete** on the tree: rename or delete.
- **Delete** in a table: remove the selected rows.
- **Ctrl+Z** / **Ctrl+Y** in the body editor or the URL field: undo / redo, separately in each tab. A URL change made from the Params table undoes as one step.
- **Ctrl+Z** / **Ctrl+Y** in a table (Params, Headers, Form, Post-processing, variables): undo / redo cell edits, added rows and removed rows. While a cell is being edited, Ctrl+Z first discards the typed text. Saving keeps the history; closing the tab discards it.
- **Ctrl+F** in the request or response body: find. **Enter** / **Shift+Enter** or **F3** / **Shift+F3** for the next / previous match; **Esc** closes it.

## Not supported

Postman scripts (other than the simple variable-setting pattern), multipart form-data and file bodies, other auth types (OAuth, digest, …), cookies management, WebSocket/GraphQL, and export back to Postman.
