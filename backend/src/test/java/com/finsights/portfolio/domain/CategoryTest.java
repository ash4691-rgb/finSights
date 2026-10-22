package com.finsights.portfolio.domain;

import org.hibernate.annotations.ColumnDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTest {

    @Test
    void realEstateColumnHasADatabaseDefault() throws NoSuchFieldException {
        // Regression test for the PR #113 prod outage: ddl-auto's ALTER TABLE for this NOT NULL
        // column failed (and was silently swallowed by Hibernate) on existing rows because the
        // column had no DB-level default to backfill them with. @ColumnDefault is what makes
        // that ALTER succeed — see UserAccount.customLayoutOnboardingDismissed for the pattern.
        Field field = Category.class.getDeclaredField("realEstate");
        assertThat(field.getAnnotation(ColumnDefault.class)).isNotNull();
    }
}
