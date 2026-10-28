package org.apache.shiro.biz.web.filter.authc.listener;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.subject.Subject;
import org.springframework.core.Ordered;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Login Listener.
 *
 * @author [@Loong Wan](https://github.com/loong10k)
 */
public interface LoginListener extends Ordered {

	void onFailure(AuthenticationToken token, Exception ex, ServletRequest request, ServletResponse response);

	void onSuccess(AuthenticationToken token, Subject subject, ServletRequest request, ServletResponse response);

	default int getOrder() {
		return Integer.MIN_VALUE;
	}
	
}
