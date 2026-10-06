package dev.deadinternet.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record Post(@NotBlank @Size(max = 100) String id,
                   @NotBlank @Size(max = 100) String author,
                   @NotBlank @Size(max = 5000) String text,
                   @NotNull Instant createdAt) {}
