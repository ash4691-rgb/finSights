package com.finsights.portfolio.dto;

/**
 * An existing holding tagged from a previous CAS import (see {@code CasImportService.
 * matchAgainstExisting}) whose ISIN wasn't found anywhere in the latest statement — the Holdings
 * page surfaces this as "to be deleted"; the actual delete only happens once the user clicks
 * Done, same as every other action a CAS import can take.
 */
public record CasHoldingToRemove(String holdingId, String name, String broker) { }
