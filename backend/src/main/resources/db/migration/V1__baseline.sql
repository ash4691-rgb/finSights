-- Baseline: the schema Hibernate's ddl-auto=update had built as of this migration's commit.
-- Existing databases (built by ddl-auto) are baselined at V1 and skip this file
-- (spring.flyway.baseline-on-migrate); only a brand-new empty database runs it.
-- Enum check constraints are omitted on purpose: enums are stored as VARCHAR so a new
-- constant never needs a migration (hibernate.type.preferred_enum_jdbc_type).
create table action_dismissals (
        deferred_until date,
        updated_at timestamp(6) with time zone not null,
        action_key varchar(512) not null,
        id varchar(255) not null,
        status varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        constraint uk_action_dismissal unique (user_id, action_key)
    );

    create table broker_connections (
        connected_at timestamp(6) with time zone not null,
        expires_at timestamp(6) with time zone,
        last_synced_at timestamp(6) with time zone,
        broker_key varchar(32) not null,
        last_sync_error varchar(512),
        access_token varchar(1024) not null,
        refresh_token varchar(1024),
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        unique (user_id, broker_key)
    );

    create table categories (
        real_estate boolean default false not null,
        sort_order integer not null,
        created_at timestamp(6) with time zone,
        updated_at timestamp(6) with time zone,
        version bigint not null,
        name varchar(128) not null,
        description varchar(1024),
        id varchar(255) not null,
        kind varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id)
    );

    create table category_valuation_methods (
        category_id varchar(255) not null,
        valuation_method varchar(255)
    );

    create table dashboard_layouts (
        created_at timestamp(6) with time zone,
        updated_at timestamp(6) with time zone,
        version bigint not null,
        page varchar(32) not null,
        config TEXT not null,
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        unique (user_id, page)
    );

    create table dismissed_news_items (
        dismissed_at timestamp(6) with time zone not null,
        link varchar(1024) not null,
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        unique (user_id, link)
    );

    create table emi_payments (
        amount numeric(20,2) not null,
        paid_on date not null,
        period date not null,
        holding_id varchar(255) not null,
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        constraint uk_emi_payment_period unique (holding_id, period)
    );

    create table goku_allowed_users (
        created_at timestamp(6) with time zone not null,
        model varchar(64),
        added_by varchar(254),
        email varchar(254) not null,
        id varchar(255) not null,
        primary key (id),
        unique (email)
    );

    create table holding_tags (
        tag varchar(48),
        holding_id varchar(255) not null
    );

    create table holdings (
        accrued_income numeric(20,2),
        area numeric(18,4),
        blocked boolean,
        current_unit_price numeric(20,4),
        current_value numeric(20,2),
        emi_amount numeric(20,2),
        emi_day_of_month integer,
        expected_interest_rate numeric(10,6),
        fixed_annual_rate numeric(10,6),
        fixed_rate_end_date date,
        fixed_rate_start_date date,
        invested_value numeric(20,2),
        liquid_within_seven_days boolean,
        loan_term_months integer,
        monthly_contribution numeric(20,2),
        purchase_unit_price numeric(20,4),
        quantity numeric(24,8),
        realised_profit_loss numeric(20,2),
        repayment_due_date date,
        sort_order integer not null,
        last_contribution_month varchar(7),
        created_at timestamp(6) with time zone,
        price_updated_at timestamp(6) with time zone,
        updated_at timestamp(6) with time zone,
        version bigint not null,
        area_unit varchar(16),
        manual_type varchar(16),
        ticker_symbol varchar(24),
        broker varchar(96) not null,
        name varchar(128) not null,
        broker_sync_ref varchar(160),
        notes varchar(1000),
        description varchar(1024),
        category_id varchar(255) not null,
        compounding_frequency varchar(255),
        currency varchar(255),
        holding_ref varchar(255) unique,
        id varchar(255) not null,
        repayment_frequency varchar(255),
        user_id varchar(255) not null,
        valuation_method varchar(255) not null,
        primary key (id)
    );

    create table live_prices (
        price numeric(24,8) not null,
        currency varchar(8) not null,
        fetched_at timestamp(6) with time zone not null,
        symbol varchar(24) not null,
        name varchar(128) not null,
        primary key (symbol)
    );

    create table market_history_cache (
        currency varchar(8),
        fetched_at timestamp(6) with time zone not null,
        symbol varchar(24) not null unique,
        id varchar(255) not null,
        points_json TEXT not null,
        primary key (id)
    );

    create table movement_thresholds (
        daily_percent numeric(6,2),
        half_yearly_percent numeric(6,2),
        monthly_percent numeric(6,2),
        quarterly_percent numeric(6,2),
        weekly_percent numeric(6,2),
        yearly_percent numeric(6,2),
        updated_at timestamp(6) with time zone,
        id varchar(255) not null,
        user_id varchar(255) not null unique,
        primary key (id)
    );

    create table news_cache (
        fetched_at timestamp(6) with time zone not null,
        symbol varchar(24) not null unique,
        id varchar(255) not null,
        items_json TEXT not null,
        primary key (id)
    );

    create table pending_broker_holdings (
        current_value numeric(20,2),
        quantity numeric(24,8),
        as_of timestamp(6) with time zone,
        created_at timestamp(6) with time zone,
        broker_key varchar(32) not null,
        reason varchar(32) not null,
        suggested_name varchar(128) not null,
        broker_sync_ref varchar(160) not null,
        conflicting_holding_id varchar(255),
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id)
    );

    create table portfolio_snapshots (
        current_value numeric(20,2) not null,
        invested_value numeric(20,2) not null,
        week_of date not null,
        recorded_at timestamp(6) with time zone not null,
        category_id varchar(255) not null,
        category_name varchar(255) not null,
        id varchar(255) not null,
        kind varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        constraint uk_portfolio_snapshot_week unique (user_id, category_id, week_of)
    );

    create table price_history_points (
        price numeric(24,8) not null,
        price_date date not null,
        currency varchar(8),
        updated_at timestamp(6) with time zone not null,
        symbol varchar(24) not null,
        id varchar(255) not null,
        primary key (id),
        unique (symbol, price_date)
    );

    create table price_snapshots (
        amount numeric(20,4) not null,
        recorded_at timestamp(6) with time zone not null,
        id varchar(255) not null,
        subject_id varchar(255) not null,
        subject_type varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id)
    );

    create table tag_suggestions (
        usage_count integer not null,
        updated_at timestamp(6) with time zone not null,
        valuation_method varchar(20),
        category_id varchar(40),
        tag varchar(60) not null,
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        constraint uk_tag_suggestion_context unique (user_id, tag, category_id, valuation_method)
    );

    create table ticker_preferences (
        excluded boolean not null,
        starred boolean not null,
        updated_at timestamp(6) with time zone not null,
        symbol varchar(24) not null,
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id),
        unique (user_id, symbol)
    );

    create table tracked_symbols (
        first_tracked_at timestamp(6) with time zone not null,
        symbol varchar(24) not null,
        primary key (symbol)
    );

    create table transactions (
        amount numeric(20,2) not null,
        date date not null,
        interest_paid boolean,
        principal_portion numeric(20,2),
        quantity numeric(24,8),
        created_at timestamp(6) with time zone,
        notes varchar(1024),
        holding_id varchar(255) not null,
        id varchar(255) not null,
        type varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id)
    );

    create table user_persona_instrument_types (
        instrument_type varchar(255),
        persona_id varchar(255) not null
    );

    create table user_persona_platforms (
        platform varchar(96),
        persona_id varchar(255) not null
    );

    create table user_personas (
        age integer,
        used_defaults boolean not null,
        completed_at timestamp(6) with time zone,
        occupation varchar(128),
        id varchar(255) not null,
        investing_tenure varchar(255),
        investor_persona varchar(255),
        portfolio_size varchar(255),
        risk_profile varchar(255) not null,
        salary_range varchar(255),
        user_id varchar(255) not null,
        primary key (id)
    );

    create table users (
        custom_layout_onboarding_dismissed boolean default false not null,
        daily_threshold_percent numeric(6,2),
        email_verified boolean default true not null,
        monthly_threshold_percent numeric(6,2),
        notify_email boolean not null,
        notify_push boolean not null,
        notify_sms boolean not null,
        notify_threshold_percent numeric(5,2) not null,
        persona_onboarding_dismissed boolean default false not null,
        quarterly_threshold_percent numeric(6,2),
        risk_onboarding_dismissed boolean default false not null,
        user_onboarding_dismissed boolean default false not null,
        verification_resend_count integer default 0 not null,
        verification_resend_date date,
        weekly_threshold_percent numeric(6,2),
        yearly_threshold_percent numeric(6,2),
        created_at timestamp(6) with time zone not null,
        verification_token_expires_at timestamp(6) with time zone,
        base_currency varchar(255) not null,
        country varchar(255) not null,
        display_name varchar(255),
        email varchar(255) not null,
        id varchar(255) not null,
        number_format varchar(255) not null,
        password_hash varchar(255),
        phone varchar(255),
        verification_token varchar(255),
        primary key (id),
        unique (email)
    );

    create table watchlist_items (
        created_at timestamp(6) with time zone,
        currency varchar(8),
        updated_at timestamp(6) with time zone,
        version bigint not null,
        ticker_symbol varchar(24),
        name varchar(128) not null,
        notes varchar(1024),
        id varchar(255) not null,
        user_id varchar(255) not null,
        primary key (id)
    );

    alter table if exists action_dismissals 
       add constraint FKcrvbsnoguem07spne1ofn1kj 
       foreign key (user_id) 
       references users;

    alter table if exists broker_connections 
       add constraint FKtnd3xl7xl4tqwk4sjq26cl230 
       foreign key (user_id) 
       references users;

    alter table if exists categories 
       add constraint FKghuylkwuedgl2qahxjt8g41kb 
       foreign key (user_id) 
       references users;

    alter table if exists category_valuation_methods 
       add constraint FKflx3cxb7k1ac4wviyrx96t0re 
       foreign key (category_id) 
       references categories;

    alter table if exists dashboard_layouts 
       add constraint FKm3avddbd7msuiaq2tlk7qdwgq 
       foreign key (user_id) 
       references users;

    alter table if exists dismissed_news_items 
       add constraint FKlf7jvasr7y2ay35ectp2qtwr2 
       foreign key (user_id) 
       references users;

    alter table if exists emi_payments 
       add constraint FKhdcongiw6dpay02djknk8dkyw 
       foreign key (holding_id) 
       references holdings;

    alter table if exists emi_payments 
       add constraint FKr1ecf2v58ueencnltakt3j02o 
       foreign key (user_id) 
       references users;

    alter table if exists holding_tags 
       add constraint FKdlmseeyxiju6ajmu7eior5o1n 
       foreign key (holding_id) 
       references holdings;

    alter table if exists holdings 
       add constraint FK2068hpoax3xga9jvklw14npn4 
       foreign key (category_id) 
       references categories;

    alter table if exists holdings 
       add constraint FKo0m56qvi5yyl5ikolvm7ih20o 
       foreign key (user_id) 
       references users;

    alter table if exists movement_thresholds 
       add constraint FKmxr550r0mu2oeeor0v0820xiq 
       foreign key (user_id) 
       references users;

    alter table if exists pending_broker_holdings 
       add constraint FKrkph1d5bcixjxknnwm0uhim6m 
       foreign key (user_id) 
       references users;

    alter table if exists portfolio_snapshots 
       add constraint FKefr0fsa266mpxeuftv7asf0fa 
       foreign key (user_id) 
       references users;

    alter table if exists price_snapshots 
       add constraint FK9fasn5v7j8j0telxorg38vjoa 
       foreign key (user_id) 
       references users;

    alter table if exists tag_suggestions 
       add constraint FKfttsd9oaxcl1lp1ncjx6ttjme 
       foreign key (user_id) 
       references users;

    alter table if exists ticker_preferences 
       add constraint FKlrxg1v8tv8585bm2v2k52g1x8 
       foreign key (user_id) 
       references users;

    alter table if exists transactions 
       add constraint FKir746ndpgfx8uj1790l9n8de5 
       foreign key (holding_id) 
       references holdings;

    alter table if exists transactions 
       add constraint FKqwv7rmvc8va8rep7piikrojds 
       foreign key (user_id) 
       references users;

    alter table if exists user_persona_instrument_types 
       add constraint FKkp5d5mu86kxjsgnog4rpn03u6 
       foreign key (persona_id) 
       references user_personas;

    alter table if exists user_persona_platforms 
       add constraint FKkpmasxieq2pbthrfp7awikbvs 
       foreign key (persona_id) 
       references user_personas;

    alter table if exists user_personas 
       add constraint FKib9gq4mq4fu57o7lp960migqk 
       foreign key (user_id) 
       references users;

    alter table if exists watchlist_items 
       add constraint FK3hh4g1xnal6keiwwf72epes0e 
       foreign key (user_id) 
       references users;
