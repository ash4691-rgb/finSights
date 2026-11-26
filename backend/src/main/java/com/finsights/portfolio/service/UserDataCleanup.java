package com.finsights.portfolio.service;

/**
 * Implemented by any feature that stores per-user rows outside the core tables SettingsService
 * already clears. Every implementation runs when an account is deleted, before the user row.
 */
public interface UserDataCleanup {
    void deleteForUser(String userId);
}
