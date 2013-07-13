/*------------------------------------------------------------------------------
 * COPYRIGHT Ericsson 2013
 *
 * The copyright to the computer program(s) herein is the property of
 * Ericsson Inc. The programs may be used and/or copied only with written
 * permission from Ericsson Inc. or in accordance with the terms and
 * conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied.
 *----------------------------------------------------------------------------*/
package org.springside.modules.nosql.redis.elector;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springside.modules.nosql.redis.JedisTemplate;
import org.springside.modules.nosql.redis.JedisTemplate.JedisActionNoResult;
import org.springside.modules.utils.Threads;
import org.springside.modules.utils.Threads.WrapExceptionRunnable;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * 
 * The purpose of this class is define a MasterElector to select a server is a master. The client can invoke the
 * function isMaster().
 */
public class MasterElector implements Runnable {

	private static final String DEFAULT_MASTER_KEY = "master";

	private static Logger logger = LoggerFactory.getLogger(MasterElector.class);

	private static final AtomicInteger poolNumber = new AtomicInteger(1);
	private ScheduledExecutorService internalScheduledThreadPool;
	private ScheduledFuture electorJob;

	private JedisTemplate jedisTemplate;

	private int expireSeconds;
	private String hostId;
	private AtomicBoolean master = new AtomicBoolean(false);
	private String masterKey = DEFAULT_MASTER_KEY;

	public MasterElector(JedisPool jedisPool, int expireSeconds) {
		jedisTemplate = new JedisTemplate(jedisPool);
		this.expireSeconds = expireSeconds;
	}

	public boolean isMaster() {
		return master.get();
	}

	/**
	 * 启动分发线程, 自行创建scheduler线程池.
	 */
	public void start(long periodSeconds) {
		internalScheduledThreadPool = Executors.newScheduledThreadPool(1,
				Threads.buildJobFactory("Master-Elector-" + poolNumber.getAndIncrement() + "-%d"));
		start(periodSeconds, internalScheduledThreadPool);
	}

	/**
	 * 启动分发线程, 使用传入的scheduler线程池.
	 */
	public void start(long periodSeconds, ScheduledExecutorService scheduledThreadPool) {
		if (periodSeconds >= expireSeconds) {
			throw new IllegalArgumentException("periodSeconds must less than expireSeconds. periodSeconds is "
					+ periodSeconds + " expireSeconds is " + expireSeconds);
		}

		hostId = generateHostId();
		electorJob = scheduledThreadPool.scheduleAtFixedRate(new WrapExceptionRunnable(this), 0, periodSeconds,
				TimeUnit.SECONDS);
		logger.info("masterElector start, hostName:{}.", hostId);
	}

	/**
	 * 停止分发任务，如果是自行创建的threadPool则自行销毁。
	 */
	public void stop() {
		electorJob.cancel(false);

		if (internalScheduledThreadPool != null) {
			Threads.normalShutdown(internalScheduledThreadPool, 5, TimeUnit.SECONDS);
		}
	}

	protected String generateHostId() {
		String host = "localhost";
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			logger.warn("can not get hostName", e);
		}
		host = host + "-" + new SecureRandom().nextInt(10000);

		return host;
	}

	@Override
	public void run() {
		jedisTemplate.execute(new JedisActionNoResult() {// NOSONAR
					@Override
					public void action(Jedis jedis) {
						String masterFromRedis = jedis.get(masterKey);

						logger.debug("master is {}", masterFromRedis);

						// if master is null, the cluster just start or the master had crashed, try to register myself
						// as master
						if (masterFromRedis == null) {
							// use setnx to make sure only one client can register as master.
							if (jedis.setnx(masterKey, hostId) > 0) {
								jedis.expire(masterKey, expireSeconds);
								master.set(true);

								logger.info("master is changed to {}.", hostId);
								return;
							} else {
								master.set(false);
								return;
							}
						}

						// if master is myself, update the expire time.
						if (hostId.equals(masterFromRedis)) {
							jedis.expire(masterKey, expireSeconds);
							master.set(true);
							return;
						}

						master.set(false);
					}
				});
	}

	public void setMasterKey(String masterKey) {
		this.masterKey = masterKey;
	}

	// for test
	public void setHostId(String hostId) {
		this.hostId = hostId;
	}
}
