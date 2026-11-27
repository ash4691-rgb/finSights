-- Target share of total assets per category (AllocationService); null = no target.
alter table categories add column target_allocation_percent numeric(5,2);

-- Savings goals (GoalService). user_id has no FK: rows are cleared on account deletion.
create table goals (
    id varchar(255) not null,
    user_id varchar(255) not null,
    name varchar(128) not null,
    target_amount numeric(20,2) not null,
    target_date date not null,
    monthly_contribution numeric(20,2) not null,
    expected_return_percent numeric(5,2) not null,
    created_at timestamp(6) with time zone not null,
    primary key (id)
);

create table goal_categories (
    goal_id varchar(255) not null,
    category_id varchar(255) not null,
    constraint fk_goal_categories_goal foreign key (goal_id) references goals
);
