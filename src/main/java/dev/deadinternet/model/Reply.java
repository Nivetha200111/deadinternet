package dev.deadinternet.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record Reply(@NotBlank @Size(max = 100) String id,
                    @NotNull @Valid Account author,
                    @NotBlank @Size(max = 5000) String text,
                    @NotNull Instant createdAt) {}
