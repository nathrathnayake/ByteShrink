# ByteShrink

ByteShrink is a small Java 17 desktop app and command-line tool for making **lossless ZIP archives** from files and folders.

## What it does

- Adds individual files or whole folders to a ZIP archive.
- Offers ZIP/Deflate levels 0–9, from store-only to maximum compression.
- Can test every level and keep the smallest result when you set a maximum archive size.
- Verifies every archive by reading it back and checking file sizes and CRC checksums before replacing the destination.
- Keeps folder structure, supports empty folders, and avoids collisions when selected items have the same name.
- Leaves the original files untouched. Cancelling removes the temporary archive.
- Includes a command-line mode for scripts and headless systems.

## Important limit

Lossless compression cannot guarantee that any input will fit an arbitrary target size. ZIP/Deflate only removes patterns it can represent more compactly. Photos, videos, PDFs, and existing archives are often already compressed, so the ZIP can be nearly the same size or slightly larger. When a target cannot be reached, ByteShrink keeps the smallest verified ZIP it found and says so.

When a target size is set, ByteShrink writes and verifies candidates at levels 0 through 9. That can take longer and use temporary disk space for the best result found so far plus the candidate being tested. Without a target, it creates one archive at the selected level.

## Requirements

- Java 17 or newer
- No third-party libraries

## Run the desktop app

On macOS or Linux:

```bash
./run.sh
```

On Windows, run `build.bat`, then launch with `java -jar build\byteshrink.jar`.

## Command-line use

```bash
./run.sh --cli --output backup.zip --level 9 ./Documents ./notes.txt
```

To try to keep the archive under 25 MB:

```bash
./run.sh --cli --output upload.zip --target-mb 25 ./project-folder
```

`--target-mb` runs all ten ZIP levels and chooses the smallest verified output. A target value is best-effort and does not discard information.

## Build and test

```bash
./build.sh
./test.sh
```

The integration check compresses text and random binary data, checks that the entries round-trip byte for byte, and checks that an impossible target is reported accurately.

## ZIP level guide

| Level | Meaning |
|---:|---|
| 0 | Store files without compression |
| 1–2 | Faster, lighter compression |
| 3–6 | Balanced |
| 7–9 | Stronger compression, usually slower |

Compression levels are choices within ZIP/Deflate. They do not produce a guaranteed ratio or exact output size.
