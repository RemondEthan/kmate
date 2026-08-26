package com.glodon.mordor.kmate;

import java.time.LocalDateTime;

public record Message(
        String id,
        Sender sender,
        String content,
        LocalDateTime timestamp
) {}