package com.glodon.mordor.kmate.kelsy.todo;

import java.time.LocalDate;

public record TodoCard(String title, LocalDate due, TodoStatus status, String relativePath) {
}
