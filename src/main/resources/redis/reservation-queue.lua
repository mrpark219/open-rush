-- All keys share one hash tag. Every state transition runs in this script.
local sequenceKey = KEYS[1]
local waitingKey = KEYS[2]
local waitingExpiryKey = KEYS[3]
local admittedKey = KEYS[4]
local processingKey = KEYS[5]
local ticketsKey = KEYS[6]
local usersKey = KEYS[7]
local nextAdmissionKey = KEYS[8]

local operation = ARGV[1]
local userId = ARGV[2]
local ticketId = ARGV[3]
local processingToken = ARGV[4]
local admissionIntervalMs = 1000 / tonumber(ARGV[5])
local maxInFlight = tonumber(ARGV[6])
local capacity = tonumber(ARGV[7])
local waitTtlMs = tonumber(ARGV[8])
local permitTtlMs = tonumber(ARGV[9])
local processingTtlMs = tonumber(ARGV[10])
local cleanupBatchSize = tonumber(ARGV[11])

local serverTime = redis.call('TIME')
local now = tonumber(serverTime[1]) * 1000 + tonumber(serverTime[2]) / 1000

local function loadTicket(id)
    local storedTicket = redis.call('HGET', ticketsKey, id)
    if not storedTicket then
        return nil
    end

    return cjson.decode(storedTicket)
end

local function saveTicket(id, ticket)
    redis.call('HSET', ticketsKey, id, cjson.encode(ticket))
end

local function forgetTicket(id)
    local ticket = loadTicket(id)
    if ticket and redis.call('HGET', usersKey, ticket.userId) == id then
        redis.call('HDEL', usersKey, ticket.userId)
    end

    redis.call('HDEL', ticketsKey, id)
    redis.call('ZREM', waitingKey, id)
    redis.call('ZREM', waitingExpiryKey, id)
    redis.call('ZREM', admittedKey, id)
    redis.call('ZREM', processingKey, id)
end

local function activeTicket(id)
    local ticket = loadTicket(id)
    if ticket and ticket.expiresAt <= now then
        forgetTicket(id)
        return nil
    end

    return ticket
end

local function removeExpiredTickets(expiryKey)
    local expiredIds = redis.call('ZRANGEBYSCORE', expiryKey, '-inf', now, 'LIMIT', 0, cleanupBatchSize)
    for _, expiredId in ipairs(expiredIds) do
        forgetTicket(expiredId)
    end
end

local function admitNext()
    local nextAdmissionAt = tonumber(redis.call('GET', nextAdmissionKey) or '0')
    if now < nextAdmissionAt then
        return
    end

    local occupied = redis.call('ZCARD', admittedKey) + redis.call('ZCARD', processingKey)
    if occupied >= maxInFlight then
        return
    end

    -- Do not scan past a backlog of expired tickets in one script execution.
    local firstIds = redis.call('ZRANGE', waitingKey, 0, 0)
    if #firstIds == 0 then
        return
    end

    local nextId = firstIds[1]
    local ticket = activeTicket(nextId)
    if not ticket then
        return
    end

    ticket.status = 'ADMITTED'
    ticket.expiresAt = math.floor(now + permitTtlMs)

    redis.call('ZREM', waitingKey, nextId)
    redis.call('ZREM', waitingExpiryKey, nextId)
    redis.call('ZADD', admittedKey, ticket.expiresAt, nextId)
    saveTicket(nextId, ticket)

    -- No accumulated burst credits after idle time.
    redis.call('SET', nextAdmissionKey, string.format('%.3f', now + admissionIntervalMs))
end

local function advance()
    removeExpiredTickets(waitingExpiryKey)
    removeExpiredTickets(admittedKey)
    removeExpiredTickets(processingKey)

    admitNext()
end

local function ticketView(id, ticket)
    local position = ''
    if ticket.status == 'WAITING' then
        position = tostring(redis.call('ZRANK', waitingKey, id) + 1)
    end

    local expiresAt = ''
    if ticket.status ~= 'PROCESSING' then
        expiresAt = string.format('%.0f', ticket.expiresAt)
    end

    return {'OK', id, ticket.status, position, expiresAt}
end

local function join()
    local existingId = redis.call('HGET', usersKey, userId)
    if existingId then
        local existingTicket = activeTicket(existingId)
        if existingTicket then
            return ticketView(existingId, existingTicket)
        end
    end

    if redis.call('HLEN', ticketsKey) >= capacity then
        return {'QUEUE_FULL'}
    end

    local sequence = redis.call('INCR', sequenceKey)
    local ticket = {
        userId = userId,
        status = 'WAITING',
        expiresAt = math.floor(now + waitTtlMs)
    }

    saveTicket(ticketId, ticket)
    redis.call('HSET', usersKey, userId, ticketId)
    redis.call('ZADD', waitingKey, sequence, ticketId)
    redis.call('ZADD', waitingExpiryKey, ticket.expiresAt, ticketId)

    admitNext()

    return ticketView(ticketId, loadTicket(ticketId))
end

local function status()
    local ticket = activeTicket(ticketId)
    if not ticket then
        return {'QUEUE_TICKET_NOT_FOUND'}
    end

    return ticketView(ticketId, ticket)
end

local function consume()
    local ticket = activeTicket(ticketId)
    if not ticket or ticket.userId ~= userId or ticket.status ~= 'ADMITTED' then
        return {'QUEUE_ADMISSION_REQUIRED'}
    end

    ticket.status = 'PROCESSING'
    ticket.expiresAt = math.floor(now + processingTtlMs)
    ticket.processingToken = processingToken

    redis.call('ZREM', admittedKey, ticketId)
    redis.call('ZADD', processingKey, ticket.expiresAt, ticketId)
    saveTicket(ticketId, ticket)

    return {'OK'}
end

local function finish()
    local ticket = loadTicket(ticketId)
    if ticket and ticket.status == 'PROCESSING' and ticket.processingToken == processingToken then
        forgetTicket(ticketId)
    end

    advance()

    return {'OK'}
end

if operation == 'finish' then
    return finish()
end

advance()

if operation == 'join' then
    return join()
elseif operation == 'status' then
    return status()
elseif operation == 'consume' then
    return consume()
elseif operation == 'advance' then
    return {'OK'}
end

return redis.error_reply('Unknown queue operation')
