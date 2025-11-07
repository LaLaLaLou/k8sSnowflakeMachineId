package com.montnets.aim.h5.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUtil {
	private final StringRedisTemplate redisTemplate;

	/**
	 * 设置key-value，存在返回false，不存在，设置成功返回true
	 *
	 * @author  gaojs
	 * @date    2024/7/2 16:56
	 * @param   key /
	 * @param   value /
	 * @param   timeout 超时时间
	 * @param   timeUnit 单位
	 * @return  boolean
	 */
	public boolean setIfAbsent(String key, String value, long timeout, TimeUnit timeUnit) {
		ValueOperations<String, String> ops = redisTemplate.opsForValue();
		Boolean success = ops.setIfAbsent(key, value, timeout, timeUnit);
		if (success == null) {
			try {
				Thread.sleep(500);
				success = ops.setIfAbsent(key, value, timeout, timeUnit);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Redis:setIfAbsent error:{}", e.getMessage());
			}
			log.info("Redis:setIfAbsent key:{} value:{} timeout:{} success:{}", key, value, timeout, success);
		}
		return Boolean.TRUE.equals(success);
	}

}
