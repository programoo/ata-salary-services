package com.ata.salaryservices.util;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of a numeric value from a raw salary string (e.g.
 * "80000", "$24/hr", "135k"). Leading "$" is ignored, commas are stripped,
 * and a "k" suffix is expanded (135k -> 135000). Values with no recognizable
 * leading number return empty.
 */
public final class SalaryParser {

    private static final Pattern PATTERN =
            Pattern.compile("(?i)^\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k)?");

    private SalaryParser() {
    }

    public static Optional<BigDecimal> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
            if (matcher.group(2) != null) {
                value = value.multiply(BigDecimal.valueOf(1000));
            }
            return Optional.of(value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
