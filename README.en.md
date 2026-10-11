<div align="center">

<img src="docs/assets/icon.png" width="128" alt="Moment Journal">

# Moment Journal

**A smooth journaling app for recording the everyday.**

時刻日記 · 瞬间ジャーナル

[Download latest](https://github.com/OutoriNemuri/moment-journal/releases) · [Report an issue](https://github.com/OutoriNemuri/moment-journal/issues)

**English** · [简体中文](README.md) · [日本語](README.ja.md)

</div>

---

## What it's like

Moment Journal tries to stay out of the way of writing:

- **Nothing in the way.** The app opens straight into the editor — no intermediate screens.
- **Nothing to lose.** Your text is saved as soon as you stop typing, again when you leave.
- **Your data stays yours.** Journals live only on your device, and backups go to a folder you pick.

## Features

### Writing

- **Title and body**, with Markdown in the body: bold, italic, underline, strikethrough, and heading levels.
- **Insert timestamps, places and photos** from the bar above the keyboard.
- Inserts are real parts of the entry — not decoration, but content that stays in the text.

### Journals

- Organise entries by year, place, or however you like.
- The upper part of the sidebar is a **table of contents for the current journal**; the lower part is your **list of journals**.

### Four things you'll want often

A button at the top right of the editor opens a drawer with:

| | |
|---|---|
| **Copy** | Copy the body, or title + body + creation time |
| **Find** | Search this entry, this journal, or all journals |
| **Export** | TXT, Markdown, one long image, or a set of sliced images |
| **Time** | Change when this entry was created |

### Backup

- Pick a folder, and Moment Journal packs every entry and image into a `.mjb` archive there.
- Backups run **after a cold start** and **when you switch away**, or you can trigger one yourself.
- Archives can be imported back at any time, and you'll see what's inside before anything is replaced.

> **Your data stays with you.** Moment Journal does not use the network, uploads nothing, and has no server and no account.

## Download

Get the latest `.apk` from [Releases](https://github.com/OutoriNemuri/moment-journal/releases).

- Requires Android 9 or later.
- Not on any app store, so installation needs "install from unknown sources" enabled.

## FAQ

**Why is a backup folder required?**
Moment Journal needs to know where to keep backups, or your journal exists as a single copy on a single device. Backing up only **copies** your data into a folder you choose — the original stays on your device and nothing is uploaded.

**Will I lose my journal if I change phones?**
No. Copy the `.mjb` archive to the new phone and import it from Settings → Backup → Restore from archive.

**Why is there no cloud sync?**
Cloud sync (WebDAV / cloud storage / FTP) is planned. For now you can point the backup folder at a directory that a cloud app already syncs.

**Where is my journal stored?**
In a SQLite database inside the app's private storage. Backups are `.mjb` files — a ZIP containing a database snapshot and all images.

## Project status

**Beta.** Currently `0.3 (1)`, still changing quickly, so the interface and behaviour may shift.

Done so far: journal editing, journals, inserting timestamps / places / photos, Markdown styling, find, copy, export (TXT / Markdown / long image / several slicing modes), changing creation time, local backup and restore, themes (dynamic colour and built-in palettes).

Not yet: quick notes (several notes a day merged into one entry), timeline and calendar views, mood and weather, cloud backup, custom fonts and editor backgrounds.

See [FEATURES.md](FEATURES.md) for progress and plans, and [CHANGELOG](docs/CHANGELOG.md) for what changed in each release.

## Contributing

Issues and suggestions are welcome.

To build it yourself:

```bash
./gradlew assembleDebug      # output: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest  # run unit tests
python3 tools/preflight.py   # static checks, no JDK needed
```

Builds and tests run automatically on GitHub Actions.

## Licence

Not decided yet.
