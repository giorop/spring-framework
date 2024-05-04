/*
 * Copyright 2002-2024 the original author or authors.
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

package org.springframework.web.context.request.async;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.async.DeferredResult.DeferredResultHandler;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The central class for managing asynchronous request processing, mainly intended
 * as an SPI and not typically used directly by application classes.
 *
 * <p>An async scenario starts with request processing as usual in a thread (T1).
 * Concurrent request handling can be initiated by calling
 * {@link #startCallableProcessing(Callable, Object...) startCallableProcessing} or
 * {@link #startDeferredResultProcessing(DeferredResult, Object...) startDeferredResultProcessing},
 * both of which produce a result in a separate thread (T2). The result is saved
 * and the request dispatched to the container, to resume processing with the saved
 * result in a third thread (T3). Within the dispatched thread (T3), the saved
 * result can be accessed via {@link #getConcurrentResult()} or its presence
 * detected via {@link #hasConcurrentResult()}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.2
 * @see org.springframework.web.context.request.AsyncWebRequestInterceptor
 * @see org.springframework.web.servlet.AsyncHandlerInterceptor
 * @see org.springframework.web.filter.OncePerRequestFilter#shouldNotFilterAsyncDispatch
 * @see org.springframework.web.filter.OncePerRequestFilter#isAsyncDispatch
 */
public final class WebAsyncManager {

	private static final Object RESULT_NONE = new Object();

	private static final AsyncTaskExecutor DEFAULT_TASK_EXECUTOR =
			new SimpleAsyncTaskExecutor(WebAsyncManager.class.getSimpleName());

	private static final Log logger = LogFactory.getLog(WebAsyncManager.class);

	/**
	 * Log category to use for network failure after a client has gone away.
	 * @see DisconnectedClientHelper
	 */
	private static final String DISCONNECTED_CLIENT_LOG_CATEGORY =
			"org.springframework.web.server.DisconnectedClient";

	private static final DisconnectedClientHelper disconnectedClientHelper =
			new DisconnectedClientHelper(DISCONNECTED_CLIENT_LOG_CATEGORY);

	private static final CallableProcessingInterceptor timeoutCallableInterceptor =
			new TimeoutCallableProcessingInterceptor();//抛出异常

	private static final DeferredResultProcessingInterceptor timeoutDeferredResultInterceptor =
			new TimeoutDeferredResultProcessingInterceptor();


	@Nullable
	private AsyncWebRequest asyncWebRequest;

	private AsyncTaskExecutor taskExecutor = DEFAULT_TASK_EXECUTOR;//newThread

	@Nullable
	private volatile Object concurrentResult = RESULT_NONE;

	@Nullable
	private volatile Object[] concurrentResultContext;

	/*
	 * Whether the concurrentResult is an error. If such errors remain unhandled, some
	 * Servlet containers will call AsyncListener#onError at the end, after the ASYNC
	 * and/or the ERROR dispatch (Boot's case), and we need to ignore those.
	 */
	private volatile boolean errorHandlingInProgress;

	private final Map<Object, CallableProcessingInterceptor> callableInterceptors = new LinkedHashMap<>();

	private final Map<Object, DeferredResultProcessingInterceptor> deferredResultInterceptors = new LinkedHashMap<>();


	/**
	 * Package-private constructor.
	 * @see WebAsyncUtils#getAsyncManager(jakarta.servlet.ServletRequest)
	 * @see WebAsyncUtils#getAsyncManager(org.springframework.web.context.request.WebRequest)
	 */
	WebAsyncManager() {
	}


	/**
	 * Configure the {@link AsyncWebRequest} to use. This property may be set
	 * more than once during a single request to accurately reflect the current
	 * state of the request (e.g. following a forward, request/response
	 * wrapping, etc). However, it should not be set while concurrent handling
	 * is in progress, i.e. while {@link #isConcurrentHandlingStarted()} is
	 * {@code true}.
	 * @param asyncWebRequest the web request to use
	 */
	public void setAsyncWebRequest(AsyncWebRequest asyncWebRequest) {
		Assert.notNull(asyncWebRequest, "AsyncWebRequest must not be null");
		this.asyncWebRequest = asyncWebRequest;
		this.asyncWebRequest.addCompletionHandler(() -> asyncWebRequest.removeAttribute(
				WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));//任务完成时移除
	}

	/**
	 * Configure an AsyncTaskExecutor for use with concurrent processing via
	 * {@link #startCallableProcessing(Callable, Object...)}.
	 * <p>By default a {@link SimpleAsyncTaskExecutor} instance is used.
	 */
	public void setTaskExecutor(AsyncTaskExecutor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Return whether the selected handler for the current request chose to handle
	 * the request asynchronously. A return value of "true" indicates concurrent
	 * handling is under way and the response will remain open. A return value
	 * of "false" means concurrent handling was either not started or possibly
	 * that it has completed and the request was dispatched for further
	 * processing of the concurrent result.
	 */
	public boolean isConcurrentHandlingStarted() {
		return (this.asyncWebRequest != null && this.asyncWebRequest.isAsyncStarted());
	}

	/**
	 * Return whether a result value exists as a result of concurrent handling.
	 */
	public boolean hasConcurrentResult() {
		return (this.concurrentResult != RESULT_NONE);
	}

	/**
	 * Get the result from concurrent handling.
	 * @return an Object, possibly an {@code Exception} or {@code Throwable} if
	 * concurrent handling raised one
	 * @see #clearConcurrentResult()
	 */
	@Nullable
	public Object getConcurrentResult() {
		return this.concurrentResult;
	}

	/**
	 * Get the additional processing context saved at the start of concurrent handling.
	 * @see #clearConcurrentResult()
	 */
	@Nullable
	public Object[] getConcurrentResultContext() {
		return this.concurrentResultContext;
	}

	/**
	 * Get the {@link CallableProcessingInterceptor} registered under the given key.
	 * @param key the key
	 * @return the interceptor registered under that key, or {@code null} if none
	 */
	@Nullable
	public CallableProcessingInterceptor getCallableInterceptor(Object key) {
		return this.callableInterceptors.get(key);
	}

	/**
	 * Get the {@link DeferredResultProcessingInterceptor} registered under the given key.
	 * @param key the key
	 * @return the interceptor registered under that key, or {@code null} if none
	 */
	@Nullable
	public DeferredResultProcessingInterceptor getDeferredResultInterceptor(Object key) {
		return this.deferredResultInterceptors.get(key);
	}

	/**
	 * Register a {@link CallableProcessingInterceptor} under the given key.
	 * @param key the key
	 * @param interceptor the interceptor to register
	 */
	public void registerCallableInterceptor(Object key, CallableProcessingInterceptor interceptor) {
		Assert.notNull(key, "Key is required");
		Assert.notNull(interceptor, "CallableProcessingInterceptor is required");
		this.callableInterceptors.put(key, interceptor);
	}

	/**
	 * Register a {@link CallableProcessingInterceptor} without a key.
	 * The key is derived from the class name and hash code.
	 * @param interceptors one or more interceptors to register
	 */
	public void registerCallableInterceptors(CallableProcessingInterceptor... interceptors) {
		Assert.notNull(interceptors, "A CallableProcessingInterceptor is required");
		for (CallableProcessingInterceptor interceptor : interceptors) {
			String key = interceptor.getClass().getName() + ":" + interceptor.hashCode();
			this.callableInterceptors.put(key, interceptor);
		}
	}

	/**
	 * Register a {@link DeferredResultProcessingInterceptor} under the given key.
	 * @param key the key
	 * @param interceptor the interceptor to register
	 */
	public void registerDeferredResultInterceptor(Object key, DeferredResultProcessingInterceptor interceptor) {
		Assert.notNull(key, "Key is required");
		Assert.notNull(interceptor, "DeferredResultProcessingInterceptor is required");
		this.deferredResultInterceptors.put(key, interceptor);
	}

	/**
	 * Register one or more {@link DeferredResultProcessingInterceptor DeferredResultProcessingInterceptors}
	 * without a specified key. The default key is derived from the interceptor class name and hash code.
	 * @param interceptors one or more interceptors to register
	 */
	public void registerDeferredResultInterceptors(DeferredResultProcessingInterceptor... interceptors) {
		Assert.notNull(interceptors, "A DeferredResultProcessingInterceptor is required");
		for (DeferredResultProcessingInterceptor interceptor : interceptors) {
			String key = interceptor.getClass().getName() + ":" + interceptor.hashCode();
			this.deferredResultInterceptors.put(key, interceptor);
		}
	}

	/**
	 * Clear {@linkplain #getConcurrentResult() concurrentResult} and
	 * {@linkplain #getConcurrentResultContext() concurrentResultContext}.
	 */
	public void clearConcurrentResult() {
		synchronized (WebAsyncManager.this) {
			this.concurrentResult = RESULT_NONE;
			this.concurrentResultContext = null;
		}
	}

	/**
	 * Start concurrent request processing and execute the given task with an
	 * {@link #setTaskExecutor(AsyncTaskExecutor) AsyncTaskExecutor}. The result
	 * from the task execution is saved and the request dispatched in order to
	 * resume processing of that result. If the task raises an Exception then
	 * the saved result will be the raised Exception.
	 * @param callable a unit of work to be executed asynchronously
	 * @param processingContext additional context to save that can be accessed
	 * via {@link #getConcurrentResultContext()}
	 * @throws Exception if concurrent processing failed to start
	 * @see #getConcurrentResult()
	 * @see #getConcurrentResultContext()
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void startCallableProcessing(Callable<?> callable, Object... processingContext) throws Exception {
		Assert.notNull(callable, "Callable must not be null");
		startCallableProcessing(new WebAsyncTask(callable), processingContext);
	}

	/**
	 * 开启异步任务
	 * Use the given {@link WebAsyncTask} to configure the task executor as well as
	 * the timeout value of the {@code AsyncWebRequest} before delegating to
	 * {@link #startCallableProcessing(Callable, Object...)}.
	 * @param webAsyncTask a WebAsyncTask containing the target {@code Callable}
	 * @param processingContext additional context to save that can be accessed
	 * via {@link #getConcurrentResultContext()}
	 * @throws Exception if concurrent processing failed to start
	 */
	public void startCallableProcessing(final WebAsyncTask<?> webAsyncTask, Object... processingContext)
			throws Exception {

		Assert.notNull(webAsyncTask, "WebAsyncTask must not be null");
		Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

		Long timeout = webAsyncTask.getTimeout();
		if (timeout != null) {
			this.asyncWebRequest.setTimeout(timeout);
		}

		AsyncTaskExecutor executor = webAsyncTask.getExecutor();
		if (executor != null) {
			this.taskExecutor = executor;
		}

		List<CallableProcessingInterceptor> interceptors = new ArrayList<>();
		interceptors.add(webAsyncTask.getInterceptor());
		interceptors.addAll(this.callableInterceptors.values());
		interceptors.add(timeoutCallableInterceptor);

		final Callable<?> callable = webAsyncTask.getCallable();
		final CallableInterceptorChain interceptorChain = new CallableInterceptorChain(interceptors);
		//asyncWebRequest startAsy  然后执行任务 最后dispatch 通过执行任务的结果 发布通知 之后监听做出响应
		this.asyncWebRequest.addTimeoutHandler(() -> {
			if (logger.isDebugEnabled()) {
				logger.debug("Async request timeout for " + formatUri(this.asyncWebRequest));
			}
			Object result = interceptorChain.triggerAfterTimeout(this.asyncWebRequest, callable);
			if (result != CallableProcessingInterceptor.RESULT_NONE) {
				setConcurrentResultAndDispatch(result);//超时 并放入结果
			}
		});

		this.asyncWebRequest.addErrorHandler(ex -> {
			if (!this.errorHandlingInProgress) {
				if (logger.isDebugEnabled()) {
					logger.debug("Async request error for " + formatUri(this.asyncWebRequest) + ": " + ex);
				}
				Object result = interceptorChain.triggerAfterError(this.asyncWebRequest, callable, ex);
				result = (result != CallableProcessingInterceptor.RESULT_NONE ? result : ex);
				setConcurrentResultAndDispatch(result);
			}
		});

		this.asyncWebRequest.addCompletionHandler(() ->
				interceptorChain.triggerAfterCompletion(this.asyncWebRequest, callable));
		//任务前
		interceptorChain.applyBeforeConcurrentHandling(this.asyncWebRequest, callable);
		startAsyncProcessing(processingContext);//开启异步
		try {
			Future<?> future = this.taskExecutor.submit(() -> {//提交任务 用时很短
				Object result = null;
				try {
					interceptorChain.applyPreProcess(this.asyncWebRequest, callable);
					result = callable.call();
				}
				catch (Throwable ex) {
					result = ex;
				}
				finally {
					result = interceptorChain.applyPostProcess(this.asyncWebRequest, callable, result);
				}
				setConcurrentResultAndDispatch(result);
			});
			interceptorChain.setTaskFuture(future);//设置future 用于超时取消等
		}
		catch (Throwable ex) {
			Object result = interceptorChain.applyPostProcess(this.asyncWebRequest, callable, ex);
			setConcurrentResultAndDispatch(result);
		}
	}

	private void setConcurrentResultAndDispatch(@Nullable Object result) {
		synchronized (WebAsyncManager.this) {
			if (this.concurrentResult != RESULT_NONE) {
				return;
			}
			this.concurrentResult = result;
			this.errorHandlingInProgress = (result instanceof Throwable);
		}

		Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");
		if (this.asyncWebRequest.isAsyncComplete()) {
			if (logger.isDebugEnabled()) {
				logger.debug("Async result set but request already complete: " + formatUri(this.asyncWebRequest));
			}
			return;
		}
		//链接直接断开 那就没必要继续处理
		if (result instanceof Exception ex && disconnectedClientHelper.checkAndLogClientDisconnectedException(ex)) {
			return;
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Async " + (this.errorHandlingInProgress ? "error" : "result set") +
					", dispatch to " + formatUri(this.asyncWebRequest));
		}
		this.asyncWebRequest.dispatch();//结束任务
	}

	/**
	 * Start concurrent request processing and initialize the given
	 * {@link DeferredResult} with a {@link DeferredResultHandler} that saves
	 * the result and dispatches the request to resume processing of that
	 * result. The {@code AsyncWebRequest} is also updated with a completion
	 * handler that expires the {@code DeferredResult} and a timeout handler
	 * assuming the {@code DeferredResult} has a default timeout result.
	 * @param deferredResult the DeferredResult instance to initialize
	 * @param processingContext additional context to save that can be accessed
	 * via {@link #getConcurrentResultContext()}
	 * @throws Exception if concurrent processing failed to start
	 * @see #getConcurrentResult()
	 * @see #getConcurrentResultContext()
	 */
	public void startDeferredResultProcessing(
			final DeferredResult<?> deferredResult, Object... processingContext) throws Exception {

		Assert.notNull(deferredResult, "DeferredResult must not be null");
		Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

		Long timeout = deferredResult.getTimeoutValue();
		if (timeout != null) {
			this.asyncWebRequest.setTimeout(timeout);
		}

		List<DeferredResultProcessingInterceptor> interceptors = new ArrayList<>();
		interceptors.add(deferredResult.getInterceptor());
		interceptors.addAll(this.deferredResultInterceptors.values());
		interceptors.add(timeoutDeferredResultInterceptor);

		final DeferredResultInterceptorChain interceptorChain = new DeferredResultInterceptorChain(interceptors);

		this.asyncWebRequest.addTimeoutHandler(() -> {
			try {
				interceptorChain.triggerAfterTimeout(this.asyncWebRequest, deferredResult);
			}
			catch (Throwable ex) {
				setConcurrentResultAndDispatch(ex);
			}
		});

		this.asyncWebRequest.addErrorHandler(ex -> {
			if (!this.errorHandlingInProgress) {
				try {
					if (!interceptorChain.triggerAfterError(this.asyncWebRequest, deferredResult, ex)) {
						return;
					}
					deferredResult.setErrorResult(ex);
				}
				catch (Throwable interceptorEx) {
					setConcurrentResultAndDispatch(interceptorEx);
				}
			}
		});

		this.asyncWebRequest.addCompletionHandler(() ->
				interceptorChain.triggerAfterCompletion(this.asyncWebRequest, deferredResult));

		interceptorChain.applyBeforeConcurrentHandling(this.asyncWebRequest, deferredResult);
		startAsyncProcessing(processingContext);

		try {
			interceptorChain.applyPreProcess(this.asyncWebRequest, deferredResult);
			deferredResult.setResultHandler(result -> {//延时任务 等到future等完成任务 触发deferredResult的setResult  此时handleResult执行
				result = interceptorChain.applyPostProcess(this.asyncWebRequest, deferredResult, result);
				setConcurrentResultAndDispatch(result);//dispatch
			});
		}
		catch (Throwable ex) {
			setConcurrentResultAndDispatch(ex);
		}
	}

	private void startAsyncProcessing(Object[] processingContext) {
		synchronized (WebAsyncManager.this) {
			this.concurrentResult = RESULT_NONE;
			this.concurrentResultContext = processingContext;
			this.errorHandlingInProgress = false;
		}

		Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");
		this.asyncWebRequest.startAsync();//代理给request 开启异步状态
		if (logger.isDebugEnabled()) {
			logger.debug("Started async request");
		}
	}

	private static String formatUri(AsyncWebRequest asyncWebRequest) {
		HttpServletRequest request = asyncWebRequest.getNativeRequest(HttpServletRequest.class);
		return (request != null ? request.getRequestURI() : "servlet container");
	}

}
