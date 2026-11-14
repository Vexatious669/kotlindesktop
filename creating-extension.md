# Creating a provider extension (.wasm)

AniLife loads video providers as **WASI Preview 2 components** implementing the
`anilili:provider` WIT contract. This file documents that contract (inferred from
the bundled modules) and how to build/import one.

## 1. Contract (WIT)

```wit
package anilili:provider@1.0.0;

interface types {
    record query {
        anilist-id: u32,
        mal-id: option<u32>,
        titles: list<string>,
        episode: f64,
        dub: bool,
    }

    record media-stream {
        url: string,
        quality: option<string>,
        format: string,        // "hls" | "dash" | "mp4"
    }

    record server {
        name: string,
        dub: bool,
        headers: list<tuple<string, string>>,
        streams: list<media-stream>,
    }

    record info {
        name: string,
        hosts: list<string>,   // hostnames the provider may contact
    }
}

interface host {
    http: func(request: request) -> result<response, string>;
    browser-fetch: func(origin: string, request: request) -> result<response, string>;
    log: func(message: string);
    cache-get: func(key: string) -> option<string>;
    cache-set: func(key: string, value: string, ttl: u32);
}

world provider {
    import host;
    export info: func() -> info;
    export servers: func(query: query) -> result<list<server>, string>;
}
```

## 2. What the host provides
- `http(request)` — performs an HTTP(S) request. Only hosts listed in `info.hosts`
  are allowed; redirects are capped; bodies are size-limited.
- `browser-fetch(origin, request)` — same as http but adds a Referer for an allowed origin.
- `log(message)` — prints to the app's stderr.
- `cache-get` / `cache-set` — a small TTL cache shared per provider instance.

## 3. Building
1. Write your provider in Rust (recommended) using `cargo-component`:
   ```
   cargo install cargo-component
   cargo component new --target wasm32-wasip2 my-provider
   ```
2. Implement `info()` and `servers(query)` in `src/lib.rs`.
3. Build:
   ```
   cargo component build --release
   ```
   Output: `target/wasm32-wasip2/release/my_provider.wasm`
4. The file must be a **component** (magic bytes `00 61 73 6d 0d 00 01 00`), not a core module.

## 4. Importing
- Drop the `.wasm` (or a `.json` wrapper — see below) onto the Providers page, or use
  "Add provider".
- The app copies it into `%LOCALAPPDATA%\AniLife\providers\` and loads it on next refresh.

## 5. JSON wrapper format
A provider can also be shared as JSON (base64-encoded wasm):

```json
{
  "format": "anilife-provider",
  "version": 1,
  "name": "my-provider",
  "fileName": "my-provider.wasm",
  "sha256": "<sha256 of the wasm bytes>",
  "sizeBytes": 12345,
  "data": "<base64 of the wasm bytes>"
}
```

## 6. Decoded strings from `anipm.wasm`
Representative strings extracted from the bundled module (shows the interface + types):

```
anilili:provider/host@1.0.0
anilili:provider/types@1.0.0
info
hosts
anilist-id
mal-id
titles
episode
media-stream
languages
subtitle
subtitles
skip-intro
skip-outro
thumbnails
server
versions
streams
quality
height
formats
labels
messages
```

(Plus WASI imports: `wasi:io/*`, `wasi:clocks/*`, `wasi:cli/*`.)

## 7. Tips
- Keep `hosts` minimal — only the domains you actually fetch.
- Return `err(...)` with a readable message on failure; the app surfaces it.
- Respect the size/time limits: 64 MiB memory, 45s call timeout, 16 MiB HTTP bodies.
- Test locally by importing the `.wasm` and watching the app log for `[provider]` lines.
PY