# ByteShrink

ByteShrink is a small Java 17 desktop app and command-line tool for making **lossless ZIP archives** from files and folders.

## What it does

- Adds individual files or whole folders to a ZIP archive.
- Accepts files and folders by drag and drop into the desktop input list.
- Offers ZIP/Deflate levels 0–9, from store-only to maximum compression.
- Can test every level and keep the smallest result when you set a maximum archive size.
- Verifies that every planned file and folder is present, no unexpected entries exist, and each file's size and CRC match before replacing the destination.
- Keeps folder structure, supports empty folders, and avoids collisions when selected items have the same name.
- Leaves the original files untouched. Cancellation is checked during folder scanning, writing, and verification; temporary archives are removed.
- Checks for input-file changes during compression; detected changes preserve any previous destination and remove temporary archives.
- Rejects output paths that refer to an input file through a different path, such as a symbolic-link folder alias.
- Reports unsupported inputs, including symbolic links inside selected folders, instead of silently skipping them.
- Validates inputs before creating output folders, keeping newly created output folders out of the ZIP.
- Previews the planned ZIP entries, source paths, and uncompressed sizes without creating or replacing files.
- Includes a command-line mode for scripts and headless systems.
- Inspects existing ZIPs, lists their contents, and verifies every entry's size and CRC without extracting or changing files.

## Important limit

Lossless compression cannot guarantee that any input will fit an arbitrary target size. ZIP/Deflate only removes patterns it can represent more compactly. Photos, videos, PDFs, and existing archives are often already compressed, so the ZIP can be nearly the same size or slightly larger. When a target cannot be reached, ByteShrink keeps the smallest verified ZIP it found and says so.

When a target size is set, ByteShrink writes and verifies candidates at levels 0 through 9. That can take longer and use temporary disk space for the best result found so far plus the candidate being tested. Without a target, it creates one archive at the selected level.

ByteShrink checks each planned regular file's size, modification time, identity, and type before and after reading it, and checks all planned files again before replacing the destination. Detected changes or unavailable inputs cause the run to fail; pause edits to that file and retry. These checks also apply between compression levels in target-size mode. They do not create a filesystem snapshot: changes that preserve all checked metadata, newly added files, or changes after the final check may not be detected. Keep inputs stable while compression runs.

## Requirements

- Java 17 or newer
- No third-party libraries

## Run the desktop app

On macOS or Linux:

```bash
./run.sh
```

On Windows, run `build.bat`, then launch with `java -jar build\byteshrink.jar`.

The desktop input list supports multiple selection. **Remove selected** removes only the selected entries; **Clear** removes every entry from the list.

## Command-line use

To display the options without opening the desktop app:

```bash
./run.sh --help
```

`-h`, `--cli --help`, and `--cli -h` also display help and exit successfully. In `--cli` mode, help flags can also appear after other options, before `--`.

`--output`, `--level`, and `--target-mb` each require a following value. Missing or blank values produce an option-specific error and a nonzero exit status. Prefix output filenames that begin with `--` or equal `-h` with `./` so they are treated as values.

```bash
./run.sh --cli --output backup.zip --level 9 ./Documents ./notes.txt
```

To try to keep the archive under 25 MB:

```bash
./run.sh --cli --output upload.zip --target-mb 25 ./project-folder
```

`--target-mb` runs all ten ZIP levels and chooses the smallest verified output. A target value is best-effort and does not discard information.

Unknown options such as `--levle` or `-x` produce an error naming the option and a nonzero exit status before creating or replacing an archive or creating output folders. For literal input filenames beginning with `-`, use a `./` prefix or `--` before the input paths. Every argument after `--` is treated as a file or folder path:

```bash
./run.sh --cli --output backup.zip -- --level --output
```

## Preview before creating a ZIP

In the desktop app, add inputs, choose an output path and compression settings, then click **Preview ZIP…**. Scanning runs in the background with **Cancel** support. The resizable, read-only preview lists each planned ZIP entry, its file/folder type, its uncompressed size, and its absolute source path. Click column headings to sort, including sorting sizes numerically. The summary shows file/folder counts, total uncompressed bytes, and the selected level or target-size search mode.

Preview uses the same planning and input-file protection as compression: overlapping selections are deduplicated, duplicate root names receive the same suffixes, empty folders are included, unsupported links and duplicate ZIP entry names are rejected, and the chosen output is omitted when it is inside a selected folder. No ZIPs, temporary archives, or output folders are created, and an existing destination stays untouched. Preview does not ask to replace an existing file. **Create ZIP archive** performs the normal replacement confirmation and compression afterwards.

For scripts and headless systems, add `--dry-run` to the compression command:

```bash
./run.sh --cli --dry-run --output backup.zip --level 9 ./Documents ./notes.txt
```

To preview a target-size search:

```bash
./run.sh --cli --dry-run --output upload.zip --target-mb 25 ./project-folder
```

`--output` is required even for a dry run so ByteShrink can validate the destination and exclude it from selected folders. Successful previews exit with status 0 and print a summary followed by tab-separated columns: `TYPE`, `SIZE_BYTES`, `ENTRY`, and `SOURCE`. Validation errors exit with status 1. Folder sizes are zero; the total counts file content bytes. Control characters and backslashes are escaped for display. Use `--` before literal input filenames such as `--dry-run`, as with normal compression.

Remove `--dry-run` from the command to create the archive. Preview reads metadata only: it does not read or verify file contents, estimate compressed size, determine whether a target can be met, or test destination writability. It is a point-in-time view, not a filesystem snapshot. Compression always scans and validates inputs again, so later changes may alter the plan or cause a safe failure. The resulting ZIP still undergoes full entry, size, and CRC verification before replacing the destination.

## Inspect and verify an existing ZIP

In the desktop app, click **Inspect ZIP…** and choose an archive. ByteShrink reads and verifies all entries, then opens a resizable, read-only table showing entry names, file/folder types, uncompressed sizes, compressed sizes, and CRC-32 checksums. Click column headings to sort the results, including sorting sizes numerically. The summary shows file and folder counts, total content size, and ZIP size.

Use **Find entry** to filter the verified table by name without repeating inspection. Matching ignores case and treats punctuation literally. The **Show** selector switches between **All entries**, **Files only**, and **Folders only**, and combines with the name search: for example, search for `docs/` and choose **Files only** to show matching files without folder rows. The visible-entry count updates with either filter. **Clear** resets both filters, restores all entries, and preserves the chosen sort order. Summary sizes and counts always describe the full archive.

Inspection runs in the background with progress and **Cancel** support. It does not use the compression input list or output location. No contents are extracted, and the archive and original files stay unchanged on success, cancellation, or verification failure.

For scripts or a headless system:

```bash
./run.sh --inspect backup.zip
```

Or inspect a built JAR without rebuilding:

```bash
java -jar build/byteshrink.jar --inspect backup.zip
```

`--inspect` is a standalone mode. Compression options such as `--output`, `--level`, and `--target-mb` do not apply to it. Successful inspection exits with status 0 and prints a summary followed by tab-separated columns: `TYPE`, `SIZE_BYTES`, `COMPRESSED_BYTES`, `CRC32`, and `ENTRY`. Errors exit with status 1; missing or extra inspection arguments exit with status 2. `--inspect --help` displays help.

Verification reads every uncompressed byte and checks it against the ZIP's declared size and CRC, including empty entries and folder entries. Duplicate entry names, incomplete metadata, CRC/size mismatches, and archives changed during inspection are rejected. Control characters and backslashes in entry names are escaped for display; they are not renamed in the archive.

Inspection supports ZIP formats readable by Java's standard ZIP library, including stored and Deflate entries. Encrypted ZIPs, split archives, and other unsupported compression methods cannot be verified. Full verification can take time for large archives; it streams contents through a fixed-size buffer. CRC-32 checks detect accidental corruption, not authenticity, and inspection does not repair an archive.

## Build and test

```bash
./build.sh
./test.sh
```

The integration check compresses text and random binary data, checks that the entries round-trip byte for byte, and checks that an impossible target is reported accurately.
Changed-input checks grow a file during writing, edit a nested file between target-size candidates, and remove a file after ZIP verification; each case checks both new and existing destinations, source safety, and temporary-archive cleanup.
Headless Swing checks also cover removing single, adjacent, separated, and all input selections, plus removing an empty selection.
The built JAR is checked in a headless environment with help flags, missing option values, unknown options, preservation of existing outputs on argument errors, and input filenames that look like options.
Inspector checks cover ZIPs created independently and by ByteShrink, Unicode/control-character names, empty ZIPs/files/folders, corrupt payloads, duplicate entries, cancellation, changed archives, no extraction or archive modification, the desktop table with combined name/type filters and reset behaviour, and success/error behaviour through the built JAR.
Preview checks compare file hashes, modification times, and folder trees before and after successful, invalid, and cancelled previews. They cover duplicate/overlapping roots, source-to-entry mapping, symbolic-link aliases, unsupported nested links, preflight name collisions, read-only desktop sorting, Unicode/control-character display, the packaged `--dry-run` CLI, byte-for-byte agreement with the actual ZIP, and fresh scans after inputs change.

## ZIP level guide

| Level | Meaning |
|---:|---|
| 0 | Store files without compression |
| 1–2 | Faster, lighter compression |
| 3–6 | Balanced |
| 7–9 | Stronger compression, usually slower |

Compression levels are choices within ZIP/Deflate. They do not produce a guaranteed ratio or exact output size.
