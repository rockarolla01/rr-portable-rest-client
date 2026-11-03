# Standalone REST Client

A small, standalone REST client in the spirit of Postman. It's a single Java source file with no third-party libraries; it needs only JDK 25.

## Features

- Collections with nested folders, plus rename, duplicate, reorder and delete (right-click the tree).
- Environments, and collection variables. `{{var}}` placeholders work in the URL, params, headers and body.
  - The active environment wins over collection variables.
  - A few dynamic variables are built in: `{{$guid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`.
- Query parameters stay in sync with the URL in both directions. Disabled params are kept but not sent.
- Request headers, each with an enabled checkbox.
- Request body: none, plain text, JSON (with a "Format JSON" button), or `x-www-form-urlencoded` form fields (a key/value table with enabled checkboxes; keys and values are encoded for you).
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
- Everything auto-saves to `data\workspace.json` next to the source file (see [Data location](#data-location)).

## Run

```powershell
$J = "C:\Soft\jdk-25.0.5.0.2+1\bin"
& "$J\java.exe" StandaloneRestClient.java              # run from source, no build step
& "$J\java.exe" StandaloneRestClient.java --selftest   # built-in tests
```

## Data location

Collections, environments and settings are saved in one file, `standalone-rest-client\data\workspace.json` (git-ignored), next to the source file.

To use a different file, add `-Drestclient.workspace=C:\path\to\workspace.json` to the `java` command.

## Shortcuts

- **Ctrl+Enter**, or **Enter** in the URL field: send. While a request is running, Ctrl+Enter or the button cancels it.
- **F2** / **Delete** on the tree: rename or delete.
- **Delete** in a table: remove the selected rows.
- **Ctrl+Z** / **Ctrl+Y** in the body editor: undo / redo.

## Not supported

Postman scripts (other than the simple variable-setting pattern), multipart form-data and file bodies, other auth types (OAuth, digest, …), cookies management, WebSocket/GraphQL, and export back to Postman.
