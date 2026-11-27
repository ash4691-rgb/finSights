-- User override for how a holding is taxed (TaxClass); null means FinSights infers it.
alter table holdings add column tax_class varchar(32);
