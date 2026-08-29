package com.ata.salaryservices.controller;

/**
 * Relational operators supported by {@link JobDataController}'s
 * {@code field[op]=value} filter syntax.
 */
enum FilterOperator {
    EQ, NE, GT, GTE, LT, LTE;

    static FilterOperator from(String raw) {
        return valueOf(raw.toUpperCase());
    }

    boolean test(int comparison) {
        return switch (this) {
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
        };
    }
}
