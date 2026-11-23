package dev.nath.byteshrink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;

/** Dependency-free integration checks for archive creation, verification, and size targets. */
public final class ArchiveServiceSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("byteshrink-test-");
        try {
            Path folder = Files.createDirectories(root.resolve("sample-folder/nested"));
            Files.createDirectories(root.resolve("empty-folder"));
            byte[] repeated = "ByteShrink keeps the original data intact.\n".repeat(30_000).getBytes(StandardCharsets.UTF_8);
            Files.write(folder.resolve("notes.txt"), repeated);
            byte[] randomBytes = new byte[16_384];
            new Random(42).nextBytes(randomBytes);
            Files.write(root.resolve("random.bin"), randomBytes);

            Path normalArchive = root.resolve("normal.zip");
            ArchiveService.Result normal = new ArchiveService().compress(
                    List.of(root.resolve("sample-folder"), root.resolve("empty-folder"), root.resolve("random.bin")), normalArchive, 9, null,
                    (step, steps, done, total, detail) -> {}, () -> false);
            check(Files.isRegularFile(normalArchive), "normal archive was not created");
            check(normal.fileCount() == 2, "wrong file count");
            check(normal.directoryCount() == 3, "wrong directory count");
            check(normal.archiveBytes() == Files.size(normalArchive), "reported archive size is wrong");
            try (ZipFile zip = new ZipFile(normalArchive.toFile())) {
                check(zip.getEntry("sample-folder/nested/notes.txt") != null, "folder structure was not preserved");
                check(zip.getEntry("empty-folder/") != null, "empty folder was not preserved");
                check(zip.getEntry("random.bin") != null, "standalone file was not added");
                check(zip.size() == normal.fileCount() + normal.directoryCount(), "archive is missing planned entries");
                try (var input = zip.getInputStream(zip.getEntry("sample-folder/nested/notes.txt"))) {
                    check(java.util.Arrays.equals(input.readAllBytes(), repeated), "compressed text did not round-trip");
                }
                try (var input = zip.getInputStream(zip.getEntry("random.bin"))) {
                    check(java.util.Arrays.equals(input.readAllBytes(), randomBytes), "compressed binary did not round-trip");
                }
            }

            Path impossibleTargetArchive = root.resolve("tiny-target.zip");
            ArchiveService.Result tinyTarget = new ArchiveService().compress(
                    List.of(root.resolve("random.bin")), impossibleTargetArchive, 9, 1L,
                    (step, steps, done, total, detail) -> {}, () -> false);
            check(!tinyTarget.targetMet(), "a one-byte target should not be reported as met");
            check(Files.isRegularFile(impossibleTargetArchive), "best-effort archive was not kept");
            check(tinyTarget.archiveBytes() > 1, "unexpected archive size");

            Path achievableTargetArchive = root.resolve("achievable-target.zip");
            ArchiveService.Result achievableTarget = new ArchiveService().compress(
                    List.of(root.resolve("sample-folder"), root.resolve("random.bin")), achievableTargetArchive, 9, 1_000_000L,
                    (step, steps, done, total, detail) -> {}, () -> false);
            check(achievableTarget.targetMet(), "a roomy target should be reported as met");
            check(achievableTarget.archiveBytes() <= 1_000_000L, "archive exceeded an achievable target");

            Path left = Files.createDirectories(root.resolve("left"));
            Path right = Files.createDirectories(root.resolve("right"));
            Files.writeString(left.resolve("same.txt"), "left");
            Files.writeString(right.resolve("same.txt"), "right");
            Path collisionArchive = root.resolve("collisions.zip");
            new ArchiveService().compress(List.of(left.resolve("same.txt"), right.resolve("same.txt")),
                    collisionArchive, 6, null, (step, steps, done, total, detail) -> {}, () -> false);
            try (ZipFile zip = new ZipFile(collisionArchive.toFile())) {
                check(zip.getEntry("same.txt") != null && zip.getEntry("same (2).txt") != null,
                        "duplicate top-level names were not made unique");
            }

            checkInputFileProtection(root);
            checkNestedSymbolicLinks(root);
            checkOutputDirectoryPlanning(root);
            checkPlanningCancellation(root);
            checkChangingInputs(root);

            Path protectedArchive = root.resolve("protected.zip");
            byte[] keepExisting = "preserve existing destination".getBytes(StandardCharsets.UTF_8);
            Files.write(protectedArchive, keepExisting);
            boolean cancelled = false;
            try {
                new ArchiveService().compress(List.of(root.resolve("random.bin")), protectedArchive, 9, null,
                        (step, steps, done, total, detail) -> {}, () -> true);
            } catch (java.util.concurrent.CancellationException expected) {
                cancelled = true;
            }
            check(cancelled, "pre-cancelled compression did not cancel");
            check(java.util.Arrays.equals(Files.readAllBytes(protectedArchive), keepExisting),
                    "cancellation modified an existing destination");

            System.out.println("PASS: round-trip integrity, entry coverage, empty folders, target sizing, duplicate names, input-file protection, output-folder planning, and cancellation safety");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void checkChangingInputs(Path root) throws Exception {
        byte[] original = new byte[3 * 64 * 1024];
        new Random(71).nextBytes(original);
        byte[] previousArchive = "Keep the previous archive intact.".getBytes(StandardCharsets.UTF_8);
        for (String phase : List.of("growing", "edited", "removed")) {
            for (boolean existingDestination : List.of(false, true)) {
                Path directory = Files.createDirectories(root.resolve("changing-" + phase + "-" + existingDestination));
                Path inputFolder = Files.createDirectories(directory.resolve("inputs"));
                Path source = Files.write(inputFolder.resolve("notes.bin"), original);
                Path destination = directory.resolve("archive.zip");
                if (existingDestination) Files.write(destination, previousArchive);
                byte[] changedContents = java.util.Arrays.copyOf(original,
                        original.length + (phase.equals("growing") ? 1 : 0));
                changedContents[changedContents.length - 1] ^= 1;
                var changedTime = java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(source).toMillis() + 10_000);
                boolean[] changed = { false };
                boolean refused = false;
                try {
                    // Use a nested input and all-level search for edits between candidates.
                    new ArchiveService().compress(List.of(phase.equals("edited") ? inputFolder : source),
                            destination, 6, phase.equals("edited") ? 1_000_000L : null,
                            (step, steps, done, total, detail) -> {
                                boolean trigger = switch (phase) {
                                    case "growing" -> done > 0;
                                    case "edited" -> step == 1 && detail.startsWith("Verified compression level ");
                                    case "removed" -> step == steps && detail.startsWith("Verified compression level ");
                                    default -> false;
                                };
                                if (!trigger || changed[0]) return;
                                changed[0] = true;
                                try {
                                    if (phase.equals("growing")) {
                                        Files.write(source, new byte[] { 1 }, java.nio.file.StandardOpenOption.APPEND);
                                    } else if (phase.equals("edited")) {
                                        Files.write(source, changedContents);
                                        Files.setLastModifiedTime(source, changedTime);
                                    } else {
                                        Files.delete(source);
                                    }
                                } catch (IOException error) {
                                    throw new java.io.UncheckedIOException(error);
                                }
                            }, () -> false);
                } catch (IOException expected) {
                    refused = true;
                    check(expected.getMessage().contains(source.toString()), "changed-input error omitted the source path");
                }
                check(changed[0], "test did not change the input during " + phase);
                check(refused, "compression accepted an input " + phase + " during compression");
                if (existingDestination) {
                    check(java.util.Arrays.equals(Files.readAllBytes(destination), previousArchive),
                            "changed input replaced an existing destination");
                } else {
                    check(!Files.exists(destination), "changed input published a new destination");
                }
                if (phase.equals("removed")) {
                    check(!Files.exists(source), "compression recreated a removed input");
                } else {
                    check(java.util.Arrays.equals(Files.readAllBytes(source), changedContents),
                            "compression modified the externally changed input");
                }
                try (var children = Files.list(directory)) {
                    check(children.noneMatch(path -> path.getFileName().toString().startsWith(".byteshrink-")),
                            "changed-input failure left a temporary archive");
                }
            }
        }
        System.out.println("PASS: growing, edited, and removed inputs abort safely during writing, between levels, and before publication");
    }

    private static void checkNestedSymbolicLinks(Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("nested-link-source"));
        Files.writeString(source.resolve("notes.txt"), "Keep the original source intact.");
        Path targetFile = Files.writeString(root.resolve("nested-link-target.txt"), "Linked file contents.");
        Path targetDirectory = Files.createDirectories(root.resolve("nested-link-target-folder"));
        List<Path> targets = List.of(targetFile, targetDirectory, root.resolve("nested-link-missing-target"));
        Path destination = root.resolve("nested-link-protected.zip");
        byte[] original = "Keep the previous archive intact.".getBytes(StandardCharsets.UTF_8);
        Files.write(destination, original);
        for (int i = 0; i < targets.size(); i++) {
            Path link = source.resolve("link-" + i);
            try {
                Files.createSymbolicLink(link, targets.get(i));
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                System.out.println("SKIP: nested symbolic-link checks (link creation unavailable: "
                        + unavailable.getClass().getSimpleName() + ")");
                return;
            }
            try {
                boolean rejected = false;
                try {
                    new ArchiveService().compress(List.of(source), destination, 6, null,
                            (step, steps, done, total, detail) -> {}, () -> false);
                } catch (IOException expected) {
                    rejected = true;
                    check(expected.getMessage() != null && expected.getMessage().contains(link.toString()),
                            "unsupported-input error did not identify the nested link");
                }
                check(rejected, "a nested symbolic link was silently omitted");
                check(java.util.Arrays.equals(Files.readAllBytes(destination), original),
                        "a rejected nested link modified the previous archive");
            } finally {
                Files.deleteIfExists(link);
            }
        }
        check(Files.readString(source.resolve("notes.txt")).equals("Keep the original source intact."),
                "a rejected nested link modified source contents");
        System.out.println("PASS: nested file, directory, and broken symbolic links are rejected without replacing the destination");
    }

    private static void checkPlanningCancellation(Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("cancel-scan-source"));
        for (int i = 0; i < 12; i++) {
            Files.writeString(source.resolve("file-" + i + ".txt"), "Keep this source data.");
        }
        Path outputFolder = root.resolve("cancel-scan-output/nested");
        int[] polls = { 0 };
        boolean cancelled = false;
        try {
            // Allow scanning to begin, then request cancellation on a later poll.
            new ArchiveService().compress(List.of(source), outputFolder.resolve("archive.zip"), 6, null,
                    (step, steps, done, total, detail) -> {}, () -> ++polls[0] >= 5);
        } catch (java.util.concurrent.CancellationException expected) {
            cancelled = true;
        }
        check(cancelled, "cancellation during folder scanning was not observed");
        check(!Files.exists(root.resolve("cancel-scan-output")), "planning cancellation created output folders");
        check(Files.readString(source.resolve("file-0.txt")).equals("Keep this source data."),
                "planning cancellation changed source data");
        System.out.println("PASS: cancellation during folder scanning leaves output folders uncreated");
    }

    private static void checkOutputDirectoryPlanning(Path root) throws Exception {
        Path missingSource = root.resolve("missing-source");
        Path invalidOutput = missingSource.resolve("exports/archive.zip");
        boolean refused = false;
        try {
            new ArchiveService().compress(List.of(missingSource), invalidOutput, 6, null,
                    (step, steps, done, total, detail) -> {}, () -> false);
        } catch (IOException expected) {
            refused = true;
        }
        check(refused, "a missing source was not rejected before output-folder creation");
        check(!Files.exists(missingSource), "output-folder creation manufactured a missing source");

        Path source = Files.createDirectories(root.resolve("nested-output-source"));
        Files.writeString(source.resolve("notes.txt"), "Archive only the selected source contents.");
        Path output = source.resolve("new-output/nested/archive.zip");
        ArchiveService.Result result = new ArchiveService().compress(List.of(source), output, 6, null,
                (step, steps, done, total, detail) -> {}, () -> false);
        check(Files.isRegularFile(output), "nested output archive was not created");
        check(result.fileCount() == 1 && result.directoryCount() == 1,
                "new output folders were counted as selected source contents");
        try (ZipFile zip = new ZipFile(output.toFile())) {
            check(zip.getEntry("nested-output-source/notes.txt") != null, "selected source file was not archived");
            check(zip.size() == 2, "new output folders were included in the archive");
        }
    }

    private static void checkInputFileProtection(Path root) throws Exception {
        Path sourceDirectory = Files.createDirectories(root.resolve("alias-source"));
        Path source = sourceDirectory.resolve("original.txt");
        byte[] original = "Keep this original file intact.".getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        checkOverwriteRefused(source, source, original);

        Path aliasDirectory = root.resolve("alias-directory");
        try {
            Files.createSymbolicLink(aliasDirectory, sourceDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            System.out.println("SKIP: symbolic-link alias test (link creation unavailable: "
                    + unavailable.getClass().getSimpleName() + ")");
            return;
        }
        Path alias = aliasDirectory.resolve("original.txt");
        checkOverwriteRefused(source, alias, original);
        checkOverwriteRefused(alias, source, original);
    }

    private static void checkOverwriteRefused(Path input, Path output, byte[] original) throws Exception {
        boolean refused = false;
        try {
            new ArchiveService().compress(List.of(input), output, 6, null,
                    (step, steps, done, total, detail) -> {}, () -> false);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "compression did not reject an output referring to its input");
        check(java.util.Arrays.equals(Files.readAllBytes(input), original), "input was changed by a refused overwrite");
        check(java.util.Arrays.equals(Files.readAllBytes(output), original), "output alias no longer refers to the original data");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
