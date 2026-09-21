-- 令牌桶限流
--
-- 选课开放瞬间是脉冲流量，用令牌桶而非漏桶：
-- 令牌桶允许一定程度的突发，更贴合「开放那一秒涌入」的真实场景。
--
-- KEYS[1]  桶的 key（按学生或接口维度）
-- ARGV[1]  令牌产生速率（个/秒）
-- ARGV[2]  桶容量（可承受的突发量）
-- ARGV[3]  当前时间戳（毫秒）
-- ARGV[4]  本次请求消耗的令牌数
--
-- 返回： 1 放行 / 0 限流

local rate     = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local want     = tonumber(ARGV[4])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    ts = now
end

-- 按流逝时间补充令牌，上限为桶容量
local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + (elapsed / 1000.0) * rate)

local allowed = 0
if tokens >= want then
    tokens = tokens - want
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], 60000)
return allowed
