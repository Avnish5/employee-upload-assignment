package com.example.employees.importer;

/** File-level problem: nothing in the file is processed. */
public class InvalidFileException extends Exception {
    public InvalidFileException(String message) {
        super(message);
    }
}
