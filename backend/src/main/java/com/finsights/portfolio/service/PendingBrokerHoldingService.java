package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.PendingBrokerHolding;
import com.finsights.portfolio.domain.PendingBrokerHoldingReason;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.PendingBrokerHoldingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The user-facing, one-time side of a broker-synced instrument {@link BrokerHoldingSyncConsumer}
 * couldn't auto-apply — surfacing each one in the Action Centre ({@link #actionItems}) and
 * letting the user either create a new holding for it ({@link #createFrom}) or link it onto an
 * existing one ({@link #linkToExisting}). "Dismiss" needs nothing special here: the Action
 * Centre's existing generic done/defer/delete tools already work for these rows (see {@code
 * ActionDismissalService}) — only resolving (create/link) needs its own endpoint, since those
 * actually change what a future sync matches against.
 */
@Service
public class PendingBrokerHoldingService {

    private final PendingBrokerHoldingRepository pending;
    private final HoldingRepository holdings;
    private final HoldingService holdingService;
    private final CurrentUserService currentUser;

    public PendingBrokerHoldingService(PendingBrokerHoldingRepository pending, HoldingRepository holdings,
            HoldingService holdingService, CurrentUserService currentUser) {
        this.pending = pending;
        this.holdings = holdings;
        this.holdingService = holdingService;
        this.currentUser = currentUser;
    }

    public List<ActionItemResponse> actionItems() {
        String userId = currentUser.currentUser().getId();
        return pending.findByUser_Id(userId).stream().map(this::toActionItem).toList();
    }

    private ActionItemResponse toActionItem(PendingBrokerHolding row) {
        boolean conflict = row.getReason() == PendingBrokerHoldingReason.NAME_CONFLICT;
        String kind = conflict ? "BROKER_SYNC_CONFLICT" : "BROKER_SYNC_NEW";
        String broker = brokerDisplayName(row.getBrokerKey());
        String title = (conflict ? "Possible duplicate — " : "New from " + broker + " — ") + row.getSuggestedName();
        String detail = conflict
                ? "Synced from " + broker + ", and an existing holding at the same broker already has this exact "
                        + "name. Link them if it's the same position, or review the existing one first."
                : "Synced from " + broker + " — pick a category to add it as a holding.";
        return new ActionItemResponse(kind, "WARN", title, detail,
                conflict ? row.getConflictingHoldingId() : null, row.getSuggestedName(), broker,
                null, row.getCurrentValue(), null, row.getId(),
                ActionItemResponse.keyOf(kind, row.getId(), null));
    }

    /** Creates a new BROKER_SYNC holding from a pending instrument, under the user-chosen
     *  category, and links it to the synced instrument going forward. Invested value is left at
     *  zero rather than guessed at the current value — the broker never reports a reliable cost
     *  basis, and an honest zero surfaces via the existing "no invested amount" Action Centre
     *  check, prompting the user to fill in their real cost once, rather than silently showing a
     *  permanent 0% gain. */
    @Transactional
    public HoldingResponse createFrom(String pendingId, String categoryId) {
        UserAccount user = currentUser.currentUser();
        PendingBrokerHolding row = findOwned(pendingId, user.getId());
        if (categoryId == null || categoryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a category for this holding");
        }
        HoldingRequest request = new HoldingRequest(
                categoryId, row.getSuggestedName(), ValuationMethod.BROKER_SYNC, null,
                brokerDisplayName(row.getBrokerKey()), "INR", row.getQuantity(), null, null, BigDecimal.ZERO, row.getCurrentValue(),
                null, null, null, null, null, null, null, null, null, false, false, null, null, Set.of());
        HoldingResponse created = holdingService.create(request);
        Holding holding = holdings.findByIdAndUser_Id(created.id(), user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Holding vanished right after being created"));
        holding.setBrokerSyncRef(row.getBrokerSyncRef());
        holdings.save(holding);
        pending.delete(row);
        return holdingService.get(created.id());
    }

    /** Links a pending NAME_CONFLICT instrument onto the existing holding it collided with —
     *  no new holding, no new input needed, just confirming "yes, that's the same position." */
    @Transactional
    public HoldingResponse linkToExisting(String pendingId) {
        UserAccount user = currentUser.currentUser();
        PendingBrokerHolding row = findOwned(pendingId, user.getId());
        if (row.getReason() != PendingBrokerHoldingReason.NAME_CONFLICT || row.getConflictingHoldingId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This pending item has no existing holding to link to");
        }
        Holding holding = holdings.findByIdAndUser_Id(row.getConflictingHoldingId(), user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The holding this was going to link to no longer exists"));
        Category category = holding.getCategory();
        if (category != null && !category.getAllowedValuationMethods().isEmpty()
                && !category.getAllowedValuationMethods().contains(ValuationMethod.BROKER_SYNC)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"" + category.getName()
                    + "\" doesn't allow broker-synced holdings — change its allowed valuation methods first, or move this holding to a different category.");
        }
        holding.setValuationMethod(ValuationMethod.BROKER_SYNC);
        holding.setBrokerSyncRef(row.getBrokerSyncRef());
        if (row.getQuantity() != null) holding.setQuantity(row.getQuantity());
        if (row.getCurrentValue() != null) holding.setCurrentValue(row.getCurrentValue());
        holdings.save(holding);
        pending.delete(row);
        return holdingService.get(holding.getId());
    }

    private PendingBrokerHolding findOwned(String pendingId, String userId) {
        return pending.findByIdAndUser_Id(pendingId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing pending with that id"));
    }

    private static String brokerDisplayName(String brokerKey) {
        return BrokerHoldingSyncConsumer.BROKER_DISPLAY_NAMES.getOrDefault(brokerKey, brokerKey);
    }
}
