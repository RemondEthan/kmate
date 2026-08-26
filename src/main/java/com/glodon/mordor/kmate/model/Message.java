package com.glodon.mordor.kmate.model;

import java.time.LocalDateTime;

public record Message(
        String id,
        Sender sender,
        String content,
        LocalDateTime timestamp
) {}