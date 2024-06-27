/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cache.interceptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * A base {@link CacheResolver} implementation that requires the concrete
 * implementation to provide the collection of cache name(s) based on the
 * invocation context.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 4.1
 */
public abstract class AbstractCacheResolver implements CacheResolver, InitializingBean {

	@Nullable
	private CacheManager cacheManager;


	/**
	 * Construct a new {@code AbstractCacheResolver}.
	 * @see #setCacheManager
	 */
	protected AbstractCacheResolver() {
	}

	/**
	 * Construct a new {@code AbstractCacheResolver} for the given {@link CacheManager}.
	 * @param cacheManager the CacheManager to use
	 */
	protected AbstractCacheResolver(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}


	/**
	 * Set the {@link CacheManager} that this instance should use.
	 */
	public void setCacheManager(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	/**
	 * Return the {@link CacheManager} that this instance uses.
	 */
	public CacheManager getCacheManager() {
		Assert.state(this.cacheManager != null, "No CacheManager set");
		return this.cacheManager;
	}

	@Override
	public void afterPropertiesSet() {
		Assert.notNull(this.cacheManager, "CacheManager is required");
	}


	@Override
	public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
		Collection<String> cacheNames = getCacheNames(context);//获取关联的name context其本身就包含method中的相关信息，比如构造的时候就解析了上面的缓存注解等
		if (cacheNames == null) {
			return Collections.emptyList();
		}
		Collection<Cache> result = new ArrayList<>(cacheNames.size());//通过name获取实际的cache
		for (String cacheName : cacheNames) {
			Cache cache = getCacheManager().getCache(cacheName);
			if (cache == null) {
				throw new IllegalArgumentException("Cannot find cache named '" +
						cacheName + "' for " + context.getOperation());
			}
			result.add(cache);
		}
		return result;
	}

	/**
	 * 通过当前上下文获得cache names
	 * Provide the name of the cache(s) to resolve against the current cache manager.
	 * <p>It is acceptable to return {@code null} to indicate that no cache could
	 * be resolved for this invocation.
	 * @param context the context of the particular invocation
	 * @return the cache name(s) to resolve, or {@code null} if no cache should be resolved
	 */
	@Nullable
	protected abstract Collection<String> getCacheNames(CacheOperationInvocationContext<?> context);

}
