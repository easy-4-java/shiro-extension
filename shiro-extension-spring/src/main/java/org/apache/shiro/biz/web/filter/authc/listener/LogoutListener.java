package org.apache.shiro.biz.web.filter.authc.listener;

import org.apache.shiro.subject.Subject;
import org.springframework.core.Ordered;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Logout Listener.
 *
 * @author [@Loong Wan](https://github.com/loong10k)
 */
public interface LogoutListener extends Ordered {

	void beforeLogout(Subject subject, ServletRequest request, ServletResponse response);

	void onFailure(Subject subject, Exception ex);

	void onSuccess(Subject subject, ServletRequest request, ServletResponse response);

	default int getOrder() {
		return Integer.MIN_VALUE;
	}
	
}
