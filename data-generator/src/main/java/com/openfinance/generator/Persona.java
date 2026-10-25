package com.openfinance.generator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A synthetic account holder. Every value is invented; nothing maps to a real person.
 */
public record Persona(
        String customerId,
        String name,
        LocalDate dob,
        String pan,
        String mobile,
        String email,
        String employer,
        String uan,
        BigDecimal monthlySalary,
        BigDecimal monthlyRent) {
}
