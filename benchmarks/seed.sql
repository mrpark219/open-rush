\set ON_ERROR_STOP on

do $$
begin
    if current_database() <> 'open_rush_benchmark' then
        raise exception 'Only the dedicated benchmark database can be reset.';
    end if;
end $$;

begin;

truncate reservation_items, performance_seats, reservations,
         concert_purchase_quotas, performances, concerts
restart identity restrict;

insert into concerts (id, title, description, max_tickets_per_user)
values (1, 'benchmark', '', 4);

insert into performances (id, concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
select id, 1, 'benchmark', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day'
from generate_series(1, 2) id;

insert into performance_seats (id, performance_id, seat_number, price, status)
select id, case when id % 2 = 1 then 1 else 2 end, 'A-' || id, 50000, 'AVAILABLE'
from generate_series(1, greatest(:requests, 16)) id;

commit;
analyze;
