package com.palixander.scalesync.releasenotes;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseNotesValidator {
    private static final String DIRECTORY_NAME = ".release-notes";
    private static final Pattern FILE_NAME = Pattern.compile("([1-9][0-9]*)-([a-z0-9]+(?:-[a-z0-9]+)*)\\.yaml");
    private static final Pattern UNICODE_TOKEN = Pattern.compile("[\\p{L}\\p{M}]+");
    static final int MAX_FRAGMENT_BYTES = 64 * 1024;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "issue", "userVisible", "text", "reason", "flavors", "suppressReleasedChange");
    private static final Set<String> ALLOWED_FLAVORS = Set.of("personal");
    private static final Set<String> SUPPORT_FILES = Set.of("README.md", "template.yaml.example");

    private final Load yaml = new Load(LoadSettings.builder()
            .setLabel("release-note fragment")
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .build());
    private final Parse yamlParser = new Parse(LoadSettings.builder()
            .setLabel("release-note fragment")
            .build());
    private final DirectoryOpener directoryOpener;
    private final Runnable directoryOpenedHook;
    private final FragmentLister fragmentLister;

    public ReleaseNotesValidator() {
        this(ReleaseNotesValidator::openSecureDirectory, () -> { }, ReleaseNotesValidator::listFragments);
    }

    ReleaseNotesValidator(DirectoryOpener directoryOpener, Runnable directoryOpenedHook) {
        this(directoryOpener, directoryOpenedHook, ReleaseNotesValidator::listFragments);
    }

    ReleaseNotesValidator(
            DirectoryOpener directoryOpener,
            Runnable directoryOpenedHook,
            FragmentLister fragmentLister
    ) {
        this.directoryOpener = directoryOpener;
        this.directoryOpenedHook = directoryOpenedHook;
        this.fragmentLister = fragmentLister;
    }

    public int validate(Path repositoryRoot) throws ValidationException {
        Path directory = repositoryRoot.resolve(DIRECTORY_NAME);
        try (SecureDirectoryStream<Path> secureDirectory = directoryOpener.open(repositoryRoot)) {
            directoryOpenedHook.run();
            List<Path> fragments;
            try {
                fragments = fragmentLister.list(secureDirectory);
            } catch (DirectoryIteratorException exception) {
                IOException cause = exception.getCause();
                throw new ValidationException("cannot enumerate release-note directory '" + directory
                        + "': " + conciseMessage(cause), cause);
            }
            fragments.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));

            Set<String> caseInsensitiveNames = new HashSet<>();
            List<String> diagnostics = new ArrayList<>();
            int fragmentCount = 0;
            for (Path listedPath : fragments) {
                Path fileName = listedPath.getFileName();
                Path displayPath = directory.resolve(fileName);
                BasicFileAttributeView view = secureDirectory.getFileAttributeView(
                        fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes attributes = view.readAttributes();
                if (!attributes.isRegularFile()) {
                    diagnostics.add(error(displayPath, "only regular files are allowed; symbolic links are forbidden").getMessage());
                    continue;
                }
                if (SUPPORT_FILES.contains(fileName.toString())) {
                    continue;
                }
                fragmentCount++;
                String lowerCaseName = fileName.toString().toLowerCase(Locale.ROOT);
                if (!caseInsensitiveNames.add(lowerCaseName)) {
                    diagnostics.add(error(displayPath, "filename collides case-insensitively with another fragment").getMessage());
                    continue;
                }
                try {
                    validateFragment(displayPath, secureDirectory, fileName);
                } catch (ValidationException exception) {
                    diagnostics.add(exception.getMessage());
                }
            }
            if (!diagnostics.isEmpty()) {
                throw new ValidationException(String.join(System.lineSeparator(), diagnostics));
            }
            return fragmentCount;
        } catch (ValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ValidationException("cannot securely read directory '" + directory + "': " + exception.getMessage(), exception);
        }
    }

    private static SecureDirectoryStream<Path> openSecureDirectory(Path repositoryRoot) throws IOException, ValidationException {
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(repositoryRoot)) {
            if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
                throw new ValidationException("filesystem provider does not support secure directory validation for '" + repositoryRoot + "'");
            }
            DirectoryStream<Path> notesStream;
            try {
                notesStream = secureRoot.newDirectoryStream(Path.of(DIRECTORY_NAME), LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                throw new ValidationException("required directory '" + repositoryRoot.resolve(DIRECTORY_NAME)
                        + "' does not exist or is not a directory; symbolic links are forbidden", exception);
            }
            if (!(notesStream instanceof SecureDirectoryStream<Path> secureNotes)) {
                notesStream.close();
                throw new ValidationException("filesystem provider does not support secure directory validation for '"
                        + repositoryRoot.resolve(DIRECTORY_NAME) + "'");
            }
            return secureNotes;
        }
    }

    private void validateFragment(Path fragment, SecureDirectoryStream<Path> directory, Path relativeFileName) throws ValidationException {
        String fileName = fragment.getFileName().toString();
        Matcher fileNameMatcher = FILE_NAME.matcher(fileName);
        if (!fileNameMatcher.matches()) {
            throw error(fragment, "filename must match <positive-issue>-<lowercase-slug>.yaml");
        }

        String input;
        try {
            input = readFragment(directory, relativeFileName);
        } catch (CharacterCodingException exception) {
            throw error(fragment, "file must be valid UTF-8", exception);
        } catch (IOException exception) {
            throw error(fragment, "cannot read file: " + exception.getMessage(), exception);
        }

        List<Object> documents;
        try {
            yamlParser.parseString(input).forEach(event -> {
                if (event instanceof AliasEvent) {
                    throw new AliasRejectedException();
                }
            });
            documents = new ArrayList<>();
            yaml.loadAllFromString(input).forEach(documents::add);
        } catch (AliasRejectedException exception) {
            throw error(fragment, "YAML aliases are not allowed");
        } catch (YamlEngineException exception) {
            throw error(fragment, "invalid YAML: " + conciseMessage(exception), exception);
        }
        if (documents.size() != 1) {
            throw error(fragment, "must contain exactly one YAML document");
        }

        Map<String, Object> values = requireStringKeyedMap(fragment, documents.get(0));
        rejectUnknownKeys(fragment, values);

        BigInteger issue = requirePositiveIssue(fragment, values.get("issue"));
        BigInteger filenameIssue = new BigInteger(fileNameMatcher.group(1));
        if (!issue.equals(filenameIssue)) {
            throw error(fragment, "field 'issue' (" + issue + ") must match filename issue (" + filenameIssue + ")");
        }

        Object visibleValue = values.get("userVisible");
        if (!(visibleValue instanceof Boolean userVisible)) {
            throw error(fragment, "required field 'userVisible' must be a boolean");
        }

        if (userVisible) {
            String text = requireNonBlankString(fragment, values, "text");
            if (!containsRussianPhrase(text)) {
                throw error(fragment, "field 'text' must contain at least two Russian words (Unicode tokens) "
                        + "with at least two base letters each");
            }
            if (values.containsKey("reason")) {
                throw error(fragment, "field 'reason' is forbidden when 'userVisible' is true");
            }
            if (values.containsKey("suppressReleasedChange")) {
                throw error(fragment, "field 'suppressReleasedChange' is forbidden when 'userVisible' is true");
            }
        } else {
            requireNonBlankString(fragment, values, "reason");
            if (values.containsKey("text")) {
                throw error(fragment, "field 'text' is forbidden when 'userVisible' is false");
            }
        }

        if (values.containsKey("suppressReleasedChange")
                && !(values.get("suppressReleasedChange") instanceof Boolean)) {
            throw error(fragment, "field 'suppressReleasedChange' must be a boolean");
        }

        if (values.containsKey("flavors")) {
            validateFlavors(fragment, values.get("flavors"));
        }
    }

    private static boolean containsRussianPhrase(String text) {
        Matcher matcher = UNICODE_TOKEN.matcher(text);
        int qualifyingTokens = 0;
        while (matcher.find()) {
            if (isRussianToken(matcher.group()) && ++qualifyingTokens >= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRussianToken(String token) {
        int baseLetters = 0;
        var codePoints = token.codePoints().iterator();
        while (codePoints.hasNext()) {
            int codePoint = codePoints.nextInt();
            if (isMark(codePoint)) {
                continue;
            }
            if (!isRussianLetter(codePoint)) {
                return false;
            }
            baseLetters++;
        }
        return baseLetters >= 2;
    }

    private static boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isRussianLetter(int codePoint) {
        return (codePoint >= 'А' && codePoint <= 'Я')
                || (codePoint >= 'а' && codePoint <= 'я')
                || codePoint == 'Ё'
                || codePoint == 'ё';
    }

    private static List<Path> listFragments(SecureDirectoryStream<Path> directory) {
        List<Path> fragments = new ArrayList<>();
        directory.forEach(fragments::add);
        return fragments;
    }

    static String readFragment(SecureDirectoryStream<Path> directory, Path fileName) throws IOException {
        try (SeekableByteChannel channel = directory.newByteChannel(
                fileName, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int total = 0;
            while (channel.read(buffer) != -1) {
                int count = buffer.position();
                total += count;
                if (total > MAX_FRAGMENT_BYTES) {
                    throw new IOException("file exceeds maximum size of " + MAX_FRAGMENT_BYTES + " bytes");
                }
                bytes.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        }
    }

    @FunctionalInterface
    interface DirectoryOpener {
        SecureDirectoryStream<Path> open(Path repositoryRoot) throws IOException, ValidationException;
    }

    @FunctionalInterface
    interface FragmentLister {
        List<Path> list(SecureDirectoryStream<Path> directory);
    }

    private Map<String, Object> requireStringKeyedMap(Path fragment, Object document) throws ValidationException {
        if (!(document instanceof Map<?, ?> map)) {
            throw error(fragment, "YAML document must be a mapping");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw error(fragment, "all field names must be strings");
            }
            values.put(key, entry.getValue());
        }
        return values;
    }

    private void rejectUnknownKeys(Path fragment, Map<String, Object> values) throws ValidationException {
        for (String key : values.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw error(fragment, "unknown field '" + key + "'");
            }
        }
        if (!values.containsKey("issue")) {
            throw error(fragment, "required field 'issue' is missing");
        }
    }

    private BigInteger requirePositiveIssue(Path fragment, Object value) throws ValidationException {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger)) {
            throw error(fragment, "required field 'issue' must be an integer");
        }
        BigInteger issue = value instanceof BigInteger bigInteger
                ? bigInteger
                : BigInteger.valueOf(((Number) value).longValue());
        if (issue.signum() <= 0) {
            throw error(fragment, "field 'issue' must be greater than zero");
        }
        return issue;
    }

    private String requireNonBlankString(Path fragment, Map<String, Object> values, String field) throws ValidationException {
        Object value = values.get(field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw error(fragment, "required field '" + field + "' must be a non-blank string");
        }
        return stringValue;
    }

    private void validateFlavors(Path fragment, Object value) throws ValidationException {
        if (!(value instanceof List<?> flavors) || flavors.isEmpty()) {
            throw error(fragment, "optional field 'flavors' must be a non-empty list");
        }
        Set<String> unique = new HashSet<>();
        for (Object flavorValue : flavors) {
            if (!(flavorValue instanceof String flavor)) {
                throw error(fragment, "every 'flavors' item must be a string");
            }
            if (!ALLOWED_FLAVORS.contains(flavor)) {
                throw error(fragment, "unknown flavor '" + flavor + "'; allowed: personal");
            }
            if (!unique.add(flavor)) {
                throw error(fragment, "duplicate flavor '" + flavor + "'");
            }
        }
    }

    private static String conciseMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message);
    }

    private static ValidationException error(Path fragment, String message) {
        return new ValidationException(fragment + ": " + message);
    }

    private static ValidationException error(Path fragment, String message, Throwable cause) {
        return new ValidationException(fragment + ": " + message, cause);
    }

    private static final class AliasRejectedException extends RuntimeException {
    }
}
