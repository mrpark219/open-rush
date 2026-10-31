\set ON_ERROR_STOP on

select case :'scenario'
    when 'low' then :requests
    when 'hot' then least(:requests, 16)
    when 'quota' then least(:requests, 4)
end as expected \gset

with totals as (
    select (select count(*) from reservations) as reservations,
           (select count(*) from reservation_items) as items,
           (select count(*) from performance_seats where status = 'HELD') as held,
           (select coalesce(sum(allocated_quantity), 0) from concert_purchase_quotas) as allocated
), ownership_errors as (
    select i.id from reservation_items i
    join performance_seats s on s.id = i.performance_seat_id
    join reservations r on r.id = i.reservation_id
    where s.reservation_id is distinct from r.id
       or s.status <> 'HELD' or s.performance_id <> r.performance_id
), orphan_seats as (
    select s.id from performance_seats s
    where s.status <> 'AVAILABLE' and not exists (
        select 1 from reservation_items i
        where i.performance_seat_id = s.id and i.reservation_id = s.reservation_id
    )
), allocations as (
    select r.user_id, p.concert_id, count(i.id) as quantity
    from reservations r
    join performances p on p.id = r.performance_id
    join reservation_items i on i.reservation_id = r.id
    group by r.user_id, p.concert_id
), quota_errors as (
    select 1 from allocations a
    full join concert_purchase_quotas q using (user_id, concert_id)
    left join concerts c on c.id = coalesce(a.concert_id, q.concert_id)
    where a.quantity is distinct from q.allocated_quantity::bigint
       or q.allocated_quantity > c.max_tickets_per_user
)
select reservations = :expected and items = :expected
   and held = :expected and allocated = :expected
   and not exists (select 1 from ownership_errors)
   and not exists (select 1 from orphan_seats)
   and not exists (select 1 from quota_errors) as consistent
from totals \gset

\if :consistent
    \echo 'DB consistency: PASS (expected successes:' :expected ')'
\else
    \echo 'DB consistency: FAIL (expected successes:' :expected ')'
    do $$ begin raise exception 'DB consistency check failed'; end $$;
\endif
