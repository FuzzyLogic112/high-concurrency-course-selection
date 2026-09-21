-- 退选名额回补脚本
--
-- 同样必须原子：先判存在再回补，否则重复退选会把名额补多，
-- 反过来造成「余量大于实际容量」的另一种不一致。
--
-- KEYS[1]  教学班剩余名额   string
-- KEYS[2]  已选学生集合     set
-- ARGV[1]  学生 ID
--
-- 返回： 1 回补成功 / 0 该生本就不在集合中（幂等，不重复回补）/ -2 未预热

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
    return 0
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end

redis.call('SREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 1
