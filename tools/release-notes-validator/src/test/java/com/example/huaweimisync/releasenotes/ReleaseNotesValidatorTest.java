package com.example.huaweimisync.releasenotes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNotesValidatorTest {
    @TempDir
    Path repositoryRoot;

    private final ReleaseNotesValidator validator = new ReleaseNotesValidator();

    @Test
    void acceptsEmptyFragmentDirectory() throws Exception {
        Files.createDirectory(repositoryRoot.resolve(".release-notes"));

        assertEquals(0, validator.validate(repositoryRoot));
    }

    @Test
    void ignoresOnlyReservedSupportFiles() throws Exception {
        fragment("README.md", "Documentation\n");
        fragment("template.yaml.example", "issue: ISSUE_NUMBER\n");
        fragment("24-change.yaml", visible(24));

        assertEquals(1, validator.validate(repositoryRoot));

        fragment("notes.txt", "Documentation\n");
        assertInvalid("filename must match");
    }

    @Test
    void rejectsMissingFragmentDirectory() {
        assertInvalid("required directory");
    }

    @Test
    void acceptsVisibleAndInternalFragmentsAndCountsThem() throws Exception {
        fragment("24-visible-change.yaml", """
                issue: 24
                userVisible: true
                text: Исправлена синхронизация измерений
                flavors:
                  - personal
                  - huaweiEnterprise
                """);
        fragment("24-internal-change.yaml", """
                issue: 24
                userVisible: false
                reason: Изменена внутренняя проверка релизных заметок
                """);

        assertEquals(2, validator.validate(repositoryRoot));
    }

    @Test
    void acceptsMultipleFragmentsForSameIssue() throws Exception {
        fragment("24-first.yaml", visible(24));
        fragment("24-second.yaml", visible(24));

        assertEquals(2, validator.validate(repositoryRoot));
    }

    @Test
    void rejectsInvalidFilenameForms() throws Exception {
        fragment("0-change.yaml", visible(1));
        assertInvalid("filename must match");

        resetFragments();
        fragment("24-Uppercase.yaml", visible(24));
        assertInvalid("filename must match");

        resetFragments();
        fragment("24-change.yml", visible(24));
        assertInvalid("filename must match");

        resetFragments();
        fragment("24--change.yaml", visible(24));
        assertInvalid("filename must match");
    }

    @Test
    void rejectsCaseInsensitiveFilenameCollision() throws Exception {
        fragment("24-change.yaml", visible(24));
        fragment("24-CHANGE.yaml", visible(24));

        assertInvalid("collides case-insensitively");
    }

    @Test
    void rejectsNonFileDirectoryEntry() throws Exception {
        Files.createDirectories(repositoryRoot.resolve(".release-notes/24-change.yaml"));

        assertInvalid("only regular files are allowed");
    }

    @Test
    void rejectsSymlinkFragment() throws Exception {
        Path target = Files.writeString(repositoryRoot.resolve("outside.yaml"), visible(24));
        Path directory = Files.createDirectory(repositoryRoot.resolve(".release-notes"));
        createSymlinkOrSkip(directory.resolve("24-change.yaml"), target);

        assertInvalid("symbolic links are forbidden");
    }

    @Test
    void rejectsSymlinkSupportFile() throws Exception {
        Path target = Files.writeString(repositoryRoot.resolve("outside-readme.md"), "Outside\n");
        Path directory = Files.createDirectory(repositoryRoot.resolve(".release-notes"));
        createSymlinkOrSkip(directory.resolve("README.md"), target);

        assertInvalid("symbolic links are forbidden");
    }

    @Test
    void rejectsSymlinkFragmentDirectory() throws Exception {
        Path target = Files.createDirectory(repositoryRoot.resolve("outside-notes"));
        createSymlinkOrSkip(repositoryRoot.resolve(".release-notes"), target);

        assertInvalid("does not exist or is not a directory");
    }

    @Test
    void aggregatesSymlinkAndFragmentErrors() throws Exception {
        Path target = Files.writeString(repositoryRoot.resolve("outside.yaml"), visible(24));
        Path directory = Files.createDirectory(repositoryRoot.resolve(".release-notes"));
        createSymlinkOrSkip(directory.resolve("24-linked.yaml"), target);
        Files.writeString(directory.resolve("25-invalid.yaml"), "issue: 25\nuserVisible: false\n");

        ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(repositoryRoot));
        assertTrue(exception.getMessage().contains("24-linked.yaml"));
        assertTrue(exception.getMessage().contains("symbolic links are forbidden"));
        assertTrue(exception.getMessage().contains("25-invalid.yaml"));
        assertTrue(exception.getMessage().contains("field 'reason' must be a non-blank string"));
    }

    @Test
    void rejectsMissingNonIntegerNonPositiveAndMismatchedIssue() throws Exception {
        fragment("24-change.yaml", "userVisible: false\nreason: Внутреннее изменение\n");
        assertInvalid("required field 'issue' is missing");

        resetFragments();
        fragment("24-change.yaml", "issue: '24'\nuserVisible: false\nreason: Внутреннее изменение\n");
        assertInvalid("field 'issue' must be an integer");

        resetFragments();
        fragment("24-change.yaml", "issue: -1\nuserVisible: false\nreason: Внутреннее изменение\n");
        assertInvalid("field 'issue' must be greater than zero");

        resetFragments();
        fragment("24-change.yaml", visible(25));
        assertInvalid("must match filename issue");
    }

    @Test
    void acceptsPositiveIssueLargerThanLong() throws Exception {
        String issue = "999999999999999999999999999999999999";
        fragment(issue + "-change.yaml", "issue: " + issue + "\nuserVisible: false\nreason: Внутреннее изменение\n");

        assertEquals(1, validator.validate(repositoryRoot));
    }

    @Test
    void rejectsMissingOrWrongUserVisibleType() throws Exception {
        fragment("24-change.yaml", "issue: 24\ntext: Исправление\n");
        assertInvalid("field 'userVisible' must be a boolean");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: 'true'\ntext: Исправление\n");
        assertInvalid("field 'userVisible' must be a boolean");
    }

    @Test
    void visibleFragmentRequiresNonBlankRussianTextAndForbidsReason() throws Exception {
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\n");
        assertInvalid("field 'text' must be a non-blank string");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: '   '\n");
        assertInvalid("field 'text' must be a non-blank string");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Fixed syncing\n");
        assertInvalid("at least two Russian words");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Исправлена синхронизация\nreason: Внутреннее\n");
        assertInvalid("field 'reason' is forbidden");
    }

    @Test
    void visibleFragmentRequiresARealRussianPhrase() throws Exception {
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Fixed sync я\n");
        assertInvalid("at least two Russian words");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Синхронизация\n");
        assertInvalid("at least two Russian words");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Ӂӂ Її\n");
        assertInvalid("at least two Russian words");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Исправлена синхронизация Health Connect 2\n");
        assertEquals(1, validator.validate(repositoryRoot));

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: абHealthвг обновление\n");
        assertInvalid("at least two Russian words");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: исправле\u0301ние данных\n");
        assertEquals(1, validator.validate(repositoryRoot));

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: исправле\u0301ние\n");
        assertInvalid("at least two Russian words");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: исправлено \uD801\uDC00слово\n");
        assertInvalid("at least two Russian words");
    }

    @Test
    void reportsDirectoryIterationFailureClearly() throws Exception {
        Files.createDirectory(repositoryRoot.resolve(".release-notes"));
        IOException iterationFailure = new IOException("simulated directory read failure");
        ReleaseNotesValidator failingValidator = new ReleaseNotesValidator(
                ReleaseNotesValidatorTest::openSecureNotesDirectory,
                () -> { },
                directory -> {
                    throw new DirectoryIteratorException(iterationFailure);
                }
        );

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> failingValidator.validate(repositoryRoot)
        );
        assertTrue(exception.getMessage().contains("cannot enumerate release-note directory"));
        assertTrue(exception.getMessage().contains("simulated directory read failure"));
        assertEquals(iterationFailure, exception.getCause());
    }

    @Test
    void secureReaderRejectsSymbolicLinksAtOpen() throws Exception {
        Path target = Files.writeString(repositoryRoot.resolve("outside.yaml"), visible(24));
        Path directory = Files.createDirectory(repositoryRoot.resolve(".release-notes"));
        createSymlinkOrSkip(directory.resolve("24-linked.yaml"), target);

        try (var secureDirectory = openSecureNotesDirectory(repositoryRoot)) {
            assertThrows(
                    IOException.class,
                    () -> ReleaseNotesValidator.readFragment(secureDirectory, Path.of("24-linked.yaml"))
            );
        }
    }

    @Test
    void remainsAnchoredWhenFragmentDirectoryPathIsReplacedAfterOpen() throws Exception {
        fragment("24-original.yaml", visible(24));
        Path directory = repositoryRoot.resolve(".release-notes");
        Path openedDirectory = repositoryRoot.resolve("opened-release-notes");
        ReleaseNotesValidator anchoredValidator = new ReleaseNotesValidator(
                ReleaseNotesValidatorTest::openSecureNotesDirectory,
                () -> {
                    try {
                        Files.move(directory, openedDirectory);
                        Files.createDirectory(directory);
                        Files.writeString(directory.resolve("25-replacement.yaml"), "invalid YAML: [");
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
        );

        assertEquals(1, anchoredValidator.validate(repositoryRoot));
    }

    @Test
    void reportsUnsupportedSecureDirectoryProviderClearly() throws Exception {
        Files.createDirectory(repositoryRoot.resolve(".release-notes"));
        ReleaseNotesValidator unsupportedValidator = new ReleaseNotesValidator(
                root -> {
                    throw new ValidationException("filesystem provider does not support secure directory validation for '" + root + "'");
                },
                () -> { }
        );

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> unsupportedValidator.validate(repositoryRoot)
        );
        assertTrue(exception.getMessage().contains("filesystem provider does not support secure directory validation"));
    }

    @Test
    void rejectsInvalidUtf8() throws Exception {
        Path directory = Files.createDirectories(repositoryRoot.resolve(".release-notes"));
        byte[] prefix = "issue: 24\nuserVisible: false\nreason: ".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + 1);
        bytes[bytes.length - 1] = (byte) 0x80;
        Files.write(directory.resolve("24-change.yaml"), bytes);

        assertInvalid("file must be valid UTF-8");
    }

    @Test
    void rejectsOversizedFragment() throws Exception {
        Path directory = Files.createDirectories(repositoryRoot.resolve(".release-notes"));
        byte[] bytes = new byte[ReleaseNotesValidator.MAX_FRAGMENT_BYTES + 1];
        java.util.Arrays.fill(bytes, (byte) 'a');
        Files.write(directory.resolve("24-change.yaml"), bytes);

        assertInvalid("exceeds maximum size of 65536 bytes");
    }

    @Test
    void internalFragmentRequiresNonBlankReasonAndForbidsText() throws Exception {
        fragment("24-change.yaml", "issue: 24\nuserVisible: false\n");
        assertInvalid("field 'reason' must be a non-blank string");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: false\nreason: []\n");
        assertInvalid("field 'reason' must be a non-blank string");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: false\nreason: Внутреннее\ntext: Исправление\n");
        assertInvalid("field 'text' is forbidden");
    }

    @Test
    void validatesOptionalFlavors() throws Exception {
        fragment("24-change.yaml", visible(24).replace("text: Исправлена синхронизация", "text: Исправлена синхронизация\nflavors: []"));
        assertInvalid("field 'flavors' must be a non-empty list");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправлена синхронизация", "text: Исправлена синхронизация\nflavors: personal"));
        assertInvalid("field 'flavors' must be a non-empty list");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправлена синхронизация", "text: Исправлена синхронизация\nflavors: [personal, personal]"));
        assertInvalid("duplicate flavor 'personal'");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправлена синхронизация", "text: Исправлена синхронизация\nflavors: [enterprise]"));
        assertInvalid("unknown flavor 'enterprise'");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправлена синхронизация", "text: Исправлена синхронизация\nflavors: [1]"));
        assertInvalid("every 'flavors' item must be a string");
    }

    @Test
    void rejectsUnknownAndDuplicateKeys() throws Exception {
        fragment("24-change.yaml", visible(24) + "unexpected: value\n");
        assertInvalid("unknown field 'unexpected'");

        resetFragments();
        fragment("24-change.yaml", """
                issue: 24
                issue: 24
                userVisible: false
                reason: Внутреннее изменение
                """);
        assertInvalid("invalid YAML");
    }

    @Test
    void rejectsNonStringKeysAndNonMappingRoot() throws Exception {
        fragment("24-change.yaml", "24: value\nissue: 24\nuserVisible: false\nreason: Внутреннее\n");
        assertInvalid("field names must be strings");

        resetFragments();
        fragment("24-change.yaml", "- issue: 24\n- userVisible: false\n");
        assertInvalid("document must be a mapping");
    }

    @Test
    void rejectsMalformedYamlAndMultipleDocuments() throws Exception {
        fragment("24-change.yaml", "issue: [24\n");
        assertInvalid("invalid YAML");

        resetFragments();
        fragment("24-change.yaml", visible(24) + "---\n" + visible(24));
        assertInvalid("exactly one YAML document");
    }

    @Test
    void rejectsCollectionAliases() throws Exception {
        fragment("24-change.yaml", """
                issue: 24
                userVisible: true
                text: Исправление
                flavors: &flavors [personal]
                extra: *flavors
                """);

        assertInvalid("YAML aliases are not allowed");
    }

    @Test
    void rejectsScalarAliases() throws Exception {
        fragment("24-change.yaml", """
                issue: &issue 24
                userVisible: false
                reason: *issue
                """);

        assertInvalid("YAML aliases are not allowed");
    }

    @Test
    void errorsIdentifyTheFragment() throws Exception {
        fragment("24-specific-file.yaml", "issue: 24\nuserVisible: false\n");

        assertInvalid("24-specific-file.yaml");
    }

    @Test
    void aggregatesErrorsFromDifferentFragments() throws Exception {
        fragment("24-missing-reason.yaml", "issue: 24\nuserVisible: false\n");
        fragment("25-missing-text.yaml", "issue: 25\nuserVisible: true\n");

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> validator.validate(repositoryRoot)
        );

        assertTrue(exception.getMessage().contains("24-missing-reason.yaml"));
        assertTrue(exception.getMessage().contains("field 'reason' must be a non-blank string"));
        assertTrue(exception.getMessage().contains("25-missing-text.yaml"));
        assertTrue(exception.getMessage().contains("field 'text' must be a non-blank string"));
    }

    @Test
    void reportsAggregatedErrorsInDeterministicFilenameOrder() throws Exception {
        fragment("30-third.yaml", "issue: 30\nuserVisible: false\n");
        fragment("10-first.yaml", "issue: 10\nuserVisible: false\n");
        fragment("20-second.yaml", "issue: 20\nuserVisible: false\n");

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> validator.validate(repositoryRoot)
        );
        String message = exception.getMessage();

        int first = message.indexOf("10-first.yaml");
        int second = message.indexOf("20-second.yaml");
        int third = message.indexOf("30-third.yaml");
        assertTrue(first >= 0 && first < second && second < third, message);
    }

    private Path fragment(String name, String yaml) throws IOException {
        Path directory = repositoryRoot.resolve(".release-notes");
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(name), yaml);
    }

    private void resetFragments() throws IOException {
        Path directory = repositoryRoot.resolve(".release-notes");
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private void assertInvalid(String expectedMessagePart) {
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> validator.validate(repositoryRoot)
        );
        assertTrue(
                exception.getMessage().contains(expectedMessagePart),
                () -> "Expected error containing '" + expectedMessagePart + "' but got: " + exception.getMessage()
        );
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                throw exception;
            }
            assumeTrue(false, "Symbolic links are unavailable in this Windows environment: " + exception.getMessage());
        }
    }

    private static java.nio.file.SecureDirectoryStream<Path> openSecureNotesDirectory(Path root)
            throws IOException, ValidationException {
        java.nio.file.DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
        if (!(rootStream instanceof java.nio.file.SecureDirectoryStream<Path> secureRoot)) {
            rootStream.close();
            throw new ValidationException("secure directory streams unavailable");
        }
        try (secureRoot) {
            java.nio.file.DirectoryStream<Path> notes = secureRoot.newDirectoryStream(
                    Path.of(".release-notes"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!(notes instanceof java.nio.file.SecureDirectoryStream<Path> secureNotes)) {
                notes.close();
                throw new ValidationException("secure directory streams unavailable");
            }
            return secureNotes;
        }
    }

    private static String visible(int issue) {
        return "issue: " + issue + "\nuserVisible: true\ntext: Исправлена синхронизация\n";
    }
}
