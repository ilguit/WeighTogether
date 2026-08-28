package com.palixander.scalesync.releasenotes;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Usage: release-notes-validator [repository-root]");
            System.exit(2);
        }

        Path repositoryRoot = args.length == 0 ? Path.of("") : Path.of(args[0]);
        try {
            int count = new ReleaseNotesValidator().validate(repositoryRoot);
            System.out.printf("Validated %d release-note fragment(s).%n", count);
        } catch (ValidationException exception) {
            System.err.println("Release-note validation failed:");
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }
}
