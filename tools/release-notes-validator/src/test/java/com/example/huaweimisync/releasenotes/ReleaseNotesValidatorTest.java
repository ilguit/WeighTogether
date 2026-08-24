package com.example.huaweimisync.releasenotes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

        assertInvalid("only regular fragment files");
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
        assertInvalid("must contain Russian (Cyrillic) text");

        resetFragments();
        fragment("24-change.yaml", "issue: 24\nuserVisible: true\ntext: Исправление\nreason: Внутреннее\n");
        assertInvalid("field 'reason' is forbidden");
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
        fragment("24-change.yaml", visible(24).replace("text: Исправление", "text: Исправление\nflavors: []"));
        assertInvalid("field 'flavors' must be a non-empty list");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправление", "text: Исправление\nflavors: personal"));
        assertInvalid("field 'flavors' must be a non-empty list");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправление", "text: Исправление\nflavors: [personal, personal]"));
        assertInvalid("duplicate flavor 'personal'");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправление", "text: Исправление\nflavors: [enterprise]"));
        assertInvalid("unknown flavor 'enterprise'");

        resetFragments();
        fragment("24-change.yaml", visible(24).replace("text: Исправление", "text: Исправление\nflavors: [1]"));
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

    private static String visible(int issue) {
        return "issue: " + issue + "\nuserVisible: true\ntext: Исправление\n";
    }
}
