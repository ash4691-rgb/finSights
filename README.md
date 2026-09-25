# FinSights

India-first, website-first personal portfolio tracker. Tracks wealth accurately; it does
not place trades or give investment advice.

- **backend/** — Java 17 / Spring Boot 3.5 REST API (JPA, Spring Security)
- **frontend/** — React 19 + Vite single-page app

## Running locally

### Backend (port 8080)

```bash
cd backend
./mvnw spring-boot:run
```

Starts in **demo auth mode**: every `/api/**` request is treated as the user
`demo@finsights.local` (overridable with an `X-Demo-User` header). Data is stored in a
file-backed H2 database at `backend/data/` (PostgreSQL-compatible mode). Delete that
folder to reset.

To build a jar instead: `./mvnw clean package` then `java -jar target/portfolio-api-0.1.0.jar`.

### Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Expects the API at `http://localhost:8080` (override with `VITE_API_URL`).

## Google Sign-In

OAuth is wired but inactive unless the `google` Spring profile is enabled, so demo mode
never trips Spring's credential validation. To use real Google login:

```bash
cd backend
APP_AUTH_MODE=google \
SPRING_PROFILES_ACTIVE=google \
GOOGLE_CLIENT_ID=xxx \
GOOGLE_CLIENT_SECRET=yyy \
./mvnw spring-boot:run
```

Only the `openid`, `profile`, `email` scopes are requested (no Gmail access).

## What works today (Phase 1)

| Area | Status |
| --- | --- |
| Add / edit / delete assets & liabilities | ✅ |
| Broker / owner, tags, asset class, currency, liquidity, blocked flag | ✅ |
| Valuation: manual, market-price (stored), broker-sync (stored), fixed-rate compounding | ✅ |
| Fixed-rate holdings store principal, rate, compounding, start date; value is a shown audit trail (`GET /api/holdings/{id}/valuation`) | ✅ |
| Dashboard: net worth, allocation, broker/tag breakdowns, P&L, liabilities | ✅ |
| Holdings table: search, asset-class filter, inline bulk broker/tag/value edits | ✅ |
| CSV export & import (`GET/POST /api/holdings/export`, `/import`) | ✅ |
| Insights: breakdowns by class/broker/tag/currency/liquidity, movers, data-quality warnings | ✅ |
| Brokers & sources screen (derived broker groups + connector roadmap) | ✅ |
| Settings: base currency, display name, JSON export, account deletion | ✅ |
| Goku: read-only portfolio chat assistant, gated to an allowlist | ✅ (Phase 1) |

### Not yet (later phases)

Real Google OAuth verification, transaction-led cost basis, daily snapshots & historical
charts, currency conversion, Kite/Zerodha sync, PostgreSQL migrations, alerts, mobile app.

## Goku (portfolio chat assistant)

A chat assistant — a ⚡ **Goku** entry in the left-hand nav, opening a chat panel — that answers
questions over the signed-in user's own portfolio data by calling FinSights' existing read-only
endpoints as [Claude](https://claude.com) tools — never a data dump, never a write. It follows the
same rule the rest of the app does: it tracks wealth, it doesn't place trades or give investment
advice.

It's off by default for everyone except an email allowlist (Phase 1 of the rollout: just one real
account, plus the demo account so it works out of the box locally). It only needs an API key to
light up:

```bash
cd backend
ANTHROPIC_API_KEY=sk-ant-... ./mvnw spring-boot:run
```

Without `ANTHROPIC_API_KEY` set, the nav entry stays hidden entirely — that's the one thing you
have to provide yourself. `GOKU_ALLOWLIST` is a comma-separated list of emails (case-insensitive);
it defaults to `ash4691@gmail.com,demo@finsights.local` — the account this shipped for, plus demo
mode's fixed `demo@finsights.local` user (every local/undeployed request signs in as that account
unless Google OAuth is configured — see above). Other env vars: `GOKU_ENABLED` (default `true`),
`GOKU_MODEL` (default `claude-haiku-4-5-20251001`), `GOKU_DAILY_QUERY_LIMIT` (default `40`, per
user per day). `GET /api/goku/config` tells the frontend whether to render the nav entry at all;
the backend re-checks the allowlist on every `POST /api/goku/chat` regardless of what the frontend
shows.

## Tests

```bash
cd backend && ./mvnw test      # ValuationService, CsvService, Goku, and more — unit tests
cd frontend && npm run build   # type-checks and bundles
```

## CSV format

Header row (order-independent, case-insensitive). `id` is optional — when present and
owned by you the row is updated, otherwise a new holding is created.

```
id,name,kind,assetClass,valuationMethod,instrumentType,broker,ownerName,currency,
investedValue,currentValue,quantity,fixedAnnualRatePercent,compoundingFrequency,
fixedRateStartDate,liquidWithinSevenDays,blocked,tags,notes
```

- `kind`: `ASSET` | `LIABILITY`
- `fixedAnnualRatePercent`: e.g. `7.1` (stored internally as `0.071`)
- `compoundingFrequency`: `DAILY|WEEKLY|MONTHLY|QUARTERLY|HALF_YEARLY|ANNUALLY`
- `fixedRateStartDate`: `YYYY-MM-DD`
- `tags`: separated by `;`
