package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The complete new drag-and-drop order — every holding id the user owns, front to back. */
public record HoldingReorderRequest(@NotEmpty List<String> orderedIds) { }
