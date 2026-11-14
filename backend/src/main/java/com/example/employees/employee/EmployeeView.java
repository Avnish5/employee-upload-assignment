package com.example.employees.employee;

/** Employee as returned by the API. Dates are formatted DD-MM-YYYY (canonical format). */
public record EmployeeView(
        long id,
        String firstName,
        String lastName,
        String dob,
        String gender,
        String mobile,
        String email,
        String department,
        String designation,
        String dateOfJoining,
        long uploadId,
        int sourceRow) {
}
