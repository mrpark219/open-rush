\set ON_ERROR_STOP on

with expected_seats as (
    select seat_id, 2::bigint * seat_id - 1 as first_user,
           case (seat_id - 1) % 3
               when 0 then 'CANCELLED'
               when 1 then 'EXPIRED'
               else 'CONFIRMED'
           end as first_status
    from generate_series(1, :requests) seat_id
), expected_history as (
    select seat_id, first_user as user_id, first_status as status from expected_seats
    union all
    select seat_id, first_user + 1, 'CONFIRMED' from expected_seats where first_status <> 'CONFIRMED'
), actual_history as (
    select i.performance_seat_id as seat_id, r.user_id, r.status
    from reservation_items i
    join reservations r on r.id = i.reservation_id
), history_errors as (
    (select * from expected_history except all select * from actual_history)
    union all
    (select * from actual_history except all select * from expected_history)
), ownership_errors as (
    select s.id
    from performance_seats s
    left join expected_history e on e.seat_id = s.id and e.status = 'CONFIRMED'
    left join reservations r on r.id = s.reservation_id
    where (e.seat_id is null and (s.status <> 'AVAILABLE' or s.reservation_id is not null))
       or (e.seat_id is not null and (
           s.status <> 'RESERVED' or r.status is distinct from 'CONFIRMED'
           or r.user_id is distinct from e.user_id or r.performance_id is distinct from s.performance_id
       ))
), expected_quotas as (
    select user_id, 1::bigint as concert_id, case when status = 'CONFIRMED' then 1 else 0 end as quantity
    from expected_history
), quota_errors as (
    select 1
    from expected_quotas e
    full join concert_purchase_quotas q using (user_id, concert_id)
    where e.quantity is distinct from q.allocated_quantity
)
select (select count(*) from reservations) = (select count(*) from expected_history)
   and not exists (select 1 from history_errors)
   and not exists (select 1 from ownership_errors)
   and not exists (select 1 from quota_errors) as consistent \gset

\if :consistent
    \echo 'DB consistency: PASS (rehold iterations:' :requests ')'
\else
    \echo 'DB consistency: FAIL (rehold iterations:' :requests ')'
    do $$ begin raise exception 'Rehold DB consistency check failed'; end $$;
\endif
