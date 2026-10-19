package io.destinationintel.community;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Typed, validated payloads for each contribution type. */
public final class ContributionPayloads {

    private ContributionPayloads() {}

    public record Correction(@NotBlank @Size(max = 1000) String value) {}

    public record Closure(
            @NotNull @Pattern(regexp = "permanent|temporary", message = "must be permanent or temporary") String closure,
            @Size(max = 500) String details) {}

    public record Duplicate(@NotNull UUID duplicateOfEntryId) {}

    public record Incorrect(@NotBlank @Size(min = 10, max = 1000) String explanation) {}

    public record NewPlace(
            @NotNull @Pattern(regexp = "eat|drink|stay|see|do|buy", message = "must be one of eat, drink, stay, see, do, buy") String section,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(min = 20, max = 1500) String description,
            @Size(max = 300) String address,
            @Size(max = 200) String price,
            @Size(max = 200) String hours,
            @Size(max = 300) String whatToOrder,
            @DecimalMin("6.0") @DecimalMax("37.5") Double latitude,
            @DecimalMin("68.0") @DecimalMax("97.5") Double longitude,
            @Size(max = 500) String website) {}

    public record NewTip(
            @NotNull @Pattern(regexp = "getting_around|tips|neighbourhood|eat|stay|practical",
                    message = "must be one of getting_around, tips, neighbourhood, eat, stay, practical") String section,
            @Size(max = 120) String heading,
            @NotBlank @Size(min = 20, max = 1500) String text) {}
}
