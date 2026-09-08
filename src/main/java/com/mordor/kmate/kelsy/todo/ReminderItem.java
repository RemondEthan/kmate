package com.mordor.kmate.kelsy.todo;

import java.time.LocalDate;

public record ReminderItem(String title, LocalDate due, boolean overdue, String relativePath) {
}
