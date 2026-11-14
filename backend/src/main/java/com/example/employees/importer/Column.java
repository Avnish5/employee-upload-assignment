package com.example.employees.importer;

/** The nine expected columns, in canonical (report) order. */
public enum Column {
    FIRST_NAME("first_name"),
    LAST_NAME("last_name"),
    DOB("dob"),
    GENDER("gender"),
    MOBILE("mobile"),
    EMAIL("email"),
    DEPARTMENT("department"),
    DESIGNATION("designation"),
    DATE_OF_JOINING("date_of_joining");

    private final String header;

    Column(String header) {
        this.header = header;
    }

    public String header() {
        return header;
    }
}
