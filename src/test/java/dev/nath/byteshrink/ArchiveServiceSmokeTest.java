package dev.nath.byteshrink;

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
            byte[] repeated = "ByteShrink keeps the original data intact.\n".repeat(30_000).getBytes(StandardCharsets.UTF_8);
            Files.write(folder.resolve("notes.txt"), repeated);
            byte[] randomBytes = new byte[16_384];
            new Random(42).nextBytes(randomBytes);
            Files.write(root.resolve("random.bin"), randomBytes);

            Path normalArchive = root.resolve("normal.zip");
            ArchiveService.Result normal = new ArchiveService().compress(
                    List.of(root.resolve("sample-folder"), root.resolve("random.bin")), normalArchive, 9, null,
                    (step, steps, done, total, detail) -> {}, () -> false);
            check(Files.isRegularFile(normalArchive), "normal archive was not created");
            check(normal.fileCount() == 2, "wrong file count");
            check(normal.archiveBytes() == Files.size(normalArchive), "reported archive size is wrong");
            try (ZipFile zip = new ZipFile(normalArchive.toFile())) {
                check(zip.getEntry("sample-folder/nested/notes.txt") != null, "folder structure was not preserved");
                check(zip.getEntry("random.bin") != null, "standalone file was not added");
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

            System.out.println("PASS: round-trip integrity, target sizing, duplicate names, and cancellation safety");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
