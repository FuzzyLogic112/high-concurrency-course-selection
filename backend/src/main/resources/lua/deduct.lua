-- 选课名额原子预扣脚本（毕设核心代码）
--
-- Redis 单线程执行 Lua，EVAL 期间不处理其他命令，
-- 因此判重、判余量、扣减、登记四步构成不可分割的原子操作，
-- 不存在「读到旧值再扣减」的窗口 —— 这是本系统绝不超选的根本原因。
--
-- KEYS[1]  教学班剩余名额          string,  如 cs:class:1001:remain
-- KEYS[2]  该教学班已选学生集合    set,     如 cs:class:1001:selected
-- ARGV[1]  学生 ID
--
-- 返回值约定：
--    1  预扣成功
--    0  名额已满
--   -1  该学生已经选过（幂等拦截）
--   -2  名额未预热，调用方应降级走数据库悲观锁

-- 判重必须写在脚本里：放在脚本外就是两次独立往返，
-- 同一学生的两个并发请求会双双通过判重，然后各扣一个名额。
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local remain = redis.call('GET', KEYS[1])
if remain == false then
    return -2
end

if tonumber(remain) <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
