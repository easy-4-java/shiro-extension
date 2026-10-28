package org.apache.shiro.biz.web.servlet.filter;

import org.apache.shiro.biz.utils.WebThreadContext;
import org.apache.shiro.web.servlet.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/**
 * WebThreadContext对象绑定过滤器
 * @author [@Loong Wan](https://github.com/loong10k)
 */
public class HttpServletRequestBindFilter extends OncePerRequestFilter {
	
	@Override
	public void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		WebThreadContext.bindRequest(request);
		WebThreadContext.bindResponse(response);
		chain.doFilter(request, response);
	}
	
}
