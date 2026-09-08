package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The complete new drag-and-drop order — every category id the user owns, front to back. */
public record CategoryReorderRequest(@NotEmpty List<String> orderedIds) { }
