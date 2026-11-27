-- Which alert items each user has already been emailed, so the daily alert job never repeats one.
-- user_id has no FK on purpose: rows are cleared by SentAlertService on account deletion and
-- pruned after 120 days.
create table sent_alerts (
    id varchar(255) not null,
    user_id varchar(255) not null,
    alert_key varchar(512) not null,
    sent_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_sent_alert unique (user_id, alert_key)
);
