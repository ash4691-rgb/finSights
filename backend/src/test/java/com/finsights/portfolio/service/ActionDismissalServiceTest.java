package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.ActionDismissal;
import com.finsights.portfolio.domain.ActionDismissalStatus;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.DeferredActionResponse;
import com.finsights.portfolio.repository.ActionDismissalRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionDismissalServiceTest {

    @Mock ActionDismissalRepository repo;
    @Mock HoldingRepository holdings;
    @Mock CurrentUserService currentUserService;

    private ActionDismissalService service;
    private UserAccount user;
    private Holding loan;

    @BeforeEach
    void setUp() throws Exception {
        service = new ActionDismissalService(repo, holdings, currentUserService);
        user = new UserAccount("demo@finsights.local", "Demo");
        setField(user, "id", "u-1");
        when(currentUserService.currentUser()).thenReturn(user);

        loan = new Holding();
        setField(loan, "id", "h-1");
        loan.setName("HDFC Housing");
    }

    private static void setField(Object entity, String name, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    private ActionDismissal dismissal(String key, ActionDismissalStatus status, LocalDate deferredUntil) throws Exception {
        ActionDismissal d = new ActionDismissal();
        setField(d, "actionKey", key);
        setField(d, "status", status);
        setField(d, "deferredUntil", deferredUntil);
        return d;
    }

    @Test
    void deferredItemsOnlyIncludesActiveDeferralsWithAReconstructedLabel() throws Exception {
        String activeKey = ActionItemResponse.keyOf("EMI_DUE", "h-1", "2026-11-20");
        String expiredKey = ActionItemResponse.keyOf("EMI_DUE", "h-1", "2026-09-01");
        String doneKey = ActionItemResponse.keyOf("FIXED_RATE_MATURED", "h-1", null);
        when(repo.findByUser_Id("u-1")).thenReturn(List.of(
                dismissal(activeKey, ActionDismissalStatus.DEFERRED, LocalDate.now().plusDays(3)),
                dismissal(expiredKey, ActionDismissalStatus.DEFERRED, LocalDate.now().minusDays(1)),
                dismissal(doneKey, ActionDismissalStatus.DONE, null)));
        when(holdings.findByIdAndUser_Id("h-1", "u-1")).thenReturn(Optional.of(loan));

        List<DeferredActionResponse> deferred = service.deferredItems();

        assertThat(deferred).hasSize(1);
        DeferredActionResponse only = deferred.get(0);
        assertThat(only.key()).isEqualTo(activeKey);
        assertThat(only.kind()).isEqualTo("EMI_DUE");
        assertThat(only.label()).isEqualTo("EMI due");
        assertThat(only.holdingName()).isEqualTo("HDFC Housing");
        assertThat(only.deferredUntil()).isEqualTo(LocalDate.now().plusDays(3));
    }

    @Test
    void restoreDeletesTheDismissalRow() throws Exception {
        String key = ActionItemResponse.keyOf("EMI_DUE", "h-1", "2026-11-20");
        ActionDismissal row = dismissal(key, ActionDismissalStatus.DEFERRED, LocalDate.now().plusDays(3));
        when(repo.findByUser_IdAndActionKey("u-1", key)).thenReturn(Optional.of(row));

        service.restore(key);

        verify(repo).delete(row);
    }

    @Test
    void restoreIsANoOpWhenThereIsNothingToRestore() {
        when(repo.findByUser_IdAndActionKey("u-1", "missing")).thenReturn(Optional.empty());

        service.restore("missing");

        verify(repo, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
