package org.apache.shiro.biz.web.servlet.listener;

import org.apache.shiro.biz.utils.WebThreadContext;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Http Servlet Request Listener.
 *
 * @author [@Loong Wan](https://github.com/loong10k)
 */
public class HttpServletRequestListener implements ServletRequestListener {

	@Override
	public void requestDestroyed(ServletRequestEvent requestEvent) {
		
	}

	@Override
	public void requestInitialized(ServletRequestEvent requestEvent) {
		if (!(requestEvent.getServletRequest() instanceof HttpServletRequest)) {
			throw new IllegalArgumentException( "Request is not an HttpServletRequest: " + requestEvent.getServletRequest());
		}
		HttpServletRequest request = (HttpServletRequest) requestEvent.getServletRequest();
		
		WebThreadContext.bindRequest(request);
		WebThreadContext.bindServletContext(request.getServletContext());
		
	}

}
