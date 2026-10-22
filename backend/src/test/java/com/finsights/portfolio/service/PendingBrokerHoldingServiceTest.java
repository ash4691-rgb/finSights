package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.PendingBrokerHolding;
import com.finsights.portfolio.domain.PendingBrokerHoldingReason;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.PendingBrokerHoldingRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PendingBrokerHoldingServiceTest {

    @Mock PendingBrokerHoldingRepository pending;
    @Mock HoldingRepository holdings;
    @Mock HoldingService holdingService;
    @Mock CurrentUserService currentUser;

    private PendingBrokerHoldingService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new PendingBrokerHoldingService(pending, holdings, holdingService, currentUser);
        user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
    }

    private PendingBrokerHolding newRow(PendingBrokerHoldingReason reason) {
        PendingBrokerHolding row = new PendingBrokerHolding();
        row.setUser(user);
        row.setBrokerKey("kite");
        row.setBrokerSyncRef("kite:1");
        row.setSuggestedName("INFY");
        row.setQuantity(BigDecimal.TEN);
        row.setCurrentValue(new BigDecimal("1500.00"));
        row.setReason(reason);
        return row;
    }

    @Test
    void actionItemsMapsANewRowToABrokerSyncNewItem() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NEW);
        setId(row, "pending-1");
        when(pending.findByUser_Id(user.getId())).thenReturn(List.of(row));

        List<ActionItemResponse> items = service.actionItems();

        assertThat(items).hasSize(1);
        ActionItemResponse item = items.get(0);
        assertThat(item.kind()).isEqualTo("BROKER_SYNC_NEW");
        assertThat(item.holdingId()).isNull(); // nothing real to open yet
        assertThat(item.pendingId()).isEqualTo("pending-1");
        assertThat(item.title()).contains("INFY");
    }

    @Test
    void actionItemsMapsAConflictRowWithTheExistingHoldingIdSoItCanBeOpened() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NAME_CONFLICT);
        row.setConflictingHoldingId("holding-1");
        setId(row, "pending-2");
        when(pending.findByUser_Id(user.getId())).thenReturn(List.of(row));

        List<ActionItemResponse> items = service.actionItems();

        ActionItemResponse item = items.get(0);
        assertThat(item.kind()).isEqualTo("BROKER_SYNC_CONFLICT");
        assertThat(item.holdingId()).isEqualTo("holding-1");
        assertThat(item.pendingId()).isEqualTo("pending-2");
    }

    @Test
    void createFromRejectsAMissingCategory() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NEW);
        setId(row, "pending-1");
        when(pending.findByIdAndUser_Id("pending-1", user.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.createFrom("pending-1", ""))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("category");
        verify(holdingService, never()).create(any());
    }

    @Test
    void createFromBuildsABrokerSyncHoldingWithZeroInvestedValueAndLinksTheRef() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NEW);
        setId(row, "pending-1");
        when(pending.findByIdAndUser_Id("pending-1", user.getId())).thenReturn(Optional.of(row));
        HoldingResponse created = responseWithId("holding-new-1");
        when(holdingService.create(any(HoldingRequest.class))).thenReturn(created);
        Holding savedEntity = new Holding();
        when(holdings.findByIdAndUser_Id("holding-new-1", user.getId())).thenReturn(Optional.of(savedEntity));
        when(holdingService.get("holding-new-1")).thenReturn(created);

        HoldingResponse result = service.createFrom("pending-1", "cat-1");

        ArgumentCaptor<HoldingRequest> captor = ArgumentCaptor.forClass(HoldingRequest.class);
        verify(holdingService).create(captor.capture());
        HoldingRequest request = captor.getValue();
        assertThat(request.categoryId()).isEqualTo("cat-1");
        assertThat(request.name()).isEqualTo("INFY");
        assertThat(request.valuationMethod()).isEqualTo(ValuationMethod.BROKER_SYNC);
        assertThat(request.investedValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.currentValue()).isEqualByComparingTo("1500.00");
        assertThat(request.quantity()).isEqualByComparingTo("10");
        assertThat(savedEntity.getBrokerSyncRef()).isEqualTo("kite:1");
        verify(holdings).save(savedEntity);
        verify(pending).delete(row);
        assertThat(result).isEqualTo(created);
    }

    @Test
    void linkToExistingRejectsANewReasonRow() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NEW); // not a conflict
        setId(row, "pending-1");
        when(pending.findByIdAndUser_Id("pending-1", user.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.linkToExisting("pending-1"))
                .isInstanceOf(ResponseStatusException.class);
        verify(holdings, never()).save(any());
    }

    @Test
    void linkToExistingRejectsACategoryThatDoesntAllowBrokerSync() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NAME_CONFLICT);
        row.setConflictingHoldingId("holding-1");
        setId(row, "pending-1");
        when(pending.findByIdAndUser_Id("pending-1", user.getId())).thenReturn(Optional.of(row));
        Category restricted = new Category();
        restricted.setName("Strict Category");
        restricted.setKind(HoldingKind.ASSET);
        restricted.setAllowedValuationMethods(Set.of(ValuationMethod.MANUAL));
        Holding existing = new Holding();
        existing.setCategory(restricted);
        when(holdings.findByIdAndUser_Id("holding-1", user.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.linkToExisting("pending-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Strict Category");
        verify(holdings, never()).save(any());
        verify(pending, never()).delete(any());
    }

    @Test
    void linkToExistingAppliesTheSnapshotAndDeletesThePendingRow() {
        PendingBrokerHolding row = newRow(PendingBrokerHoldingReason.NAME_CONFLICT);
        row.setConflictingHoldingId("holding-1");
        setId(row, "pending-1");
        when(pending.findByIdAndUser_Id("pending-1", user.getId())).thenReturn(Optional.of(row));
        Holding existing = new Holding();
        existing.setValuationMethod(ValuationMethod.MANUAL);
        when(holdings.findByIdAndUser_Id("holding-1", user.getId())).thenReturn(Optional.of(existing));
        when(holdingService.get(any())).thenReturn(responseWithId("holding-1"));

        service.linkToExisting("pending-1");

        assertThat(existing.getBrokerSyncRef()).isEqualTo("kite:1");
        assertThat(existing.getValuationMethod()).isEqualTo(ValuationMethod.BROKER_SYNC);
        assertThat(existing.getQuantity()).isEqualByComparingTo("10");
        assertThat(existing.getCurrentValue()).isEqualByComparingTo("1500.00");
        verify(holdings).save(existing);
        verify(pending).delete(row);
    }

    private static void setId(Object entity, String id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static HoldingResponse responseWithId(String id) {
        return new HoldingResponse(id, "ref", "cat-1", "Category", "INFY", HoldingKind.ASSET,
                ValuationMethod.BROKER_SYNC, null, "Zerodha Kite", "INR", "INR",
                BigDecimal.ZERO, new BigDecimal("1500.00"), new BigDecimal("1500.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, null, null, null, null, null, null, null, null, null, null, null, false, false, null, null, Set.of(),
                Instant.now(), Instant.now(), null, false, null);
    }
}
