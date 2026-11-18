/**
 * Copyright Terracotta, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.integrations.shiro;

import org.apache.shiro.lang.ShiroException;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheException;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.lang.io.ResourceUtils;
import org.apache.shiro.lang.util.Destroyable;
import org.apache.shiro.lang.util.Initializable;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Shiro {@link CacheManager} implementation using the Ehcache 3.x framework for all cache functionality
 */
public class EhcacheShiroManager implements CacheManager, Initializable, Destroyable {

  private static final Logger log = LoggerFactory.getLogger(EhcacheShiroManager.class);

  private volatile org.ehcache.CacheManager manager;

  private volatile String cacheManagerConfigFile = "classpath:/org/ehcache/integrations/shiro/ehcache.xml";
  private volatile boolean cacheManagerImplicitlyCreated = false;

  private volatile XmlConfiguration cacheConfiguration = null;

  /**
   * Returns the wrapped {@link org.ehcache.CacheManager} instance
   *
   * @return the wrapped {@link org.ehcache.CacheManager} instance
   */
  public org.ehcache.CacheManager getCacheManager() {
    return manager;
  }

  /**
   * Sets the wrapped {@link org.ehcache.CacheManager} instance
   *
   * @param cacheManager the {@link org.ehcache.CacheManager} to be used
   */
  public synchronized void setCacheManager(org.ehcache.CacheManager cacheManager) {
    try {
      destroy();
    } catch (Exception e) {
      log.warn("The Shiro managed CacheManager threw an Exception while closing", e);
    }
    manager = cacheManager;
    cacheManagerImplicitlyCreated = false;
  }

  /**
   * Returns the resource location of the config file used to initialize a new
   * EhCache CacheManager instance.  The string can be any resource path supported by the
   * {@link org.apache.shiro.lang.io.ResourceUtils#getInputStreamForPath(String)} call.
   * <P>
   * This property is ignored if the CacheManager instance is injected directly - that is, it is only used to
   * lazily create a CacheManager if one is not already provided.
   * </P>
   *
   * @return the resource location of the config file used to initialize the wrapped
   * EhCache CacheManager instance.
   */
  public String getCacheManagerConfigFile() {
    return cacheManagerConfigFile;
  }

  /**
   * Sets the resource location of the config file used to initialize the wrapped
   * EhCache CacheManager instance.  The string can be any resource path supported by the
   * {@link org.apache.shiro.lang.io.ResourceUtils#getInputStreamForPath(String)} call.
   * <P>
   * This property is ignored if the CacheManager instance is injected directly - that is, it is only used to
   * lazily create a CacheManager if one is not already provided.
   * </P>
   *
   * @param cacheManagerConfigFile resource location of the config file used to create the wrapped
   *                               EhCache CacheManager instance.
   */
  public synchronized void setCacheManagerConfigFile(String cacheManagerConfigFile) {
    this.cacheManagerConfigFile = cacheManagerConfigFile;
    this.cacheConfiguration = null;
  }

  /**
   * {@inheritDoc}
   */
  public synchronized <K, V> Cache<K, V> getCache(String name) throws CacheException {
    log.trace("Acquiring EhcacheShiro instance named [{}]", name);

    try {
      org.ehcache.CacheManager cacheManager = ensureCacheManager();
      CacheConfiguration<?, ?> configuration = cacheManager.getRuntimeConfiguration().getCacheConfigurations().get(name);
      org.ehcache.Cache<?, ?> cache = Objects.isNull(configuration)
          ? null
          : cacheManager.getCache(name, configuration.getKeyType(), configuration.getValueType());

      if (cache == null) {
        log.info("Cache with name {} does not yet exist.  Creating now.", name);
        cache = createCache(name);
        log.info("Added EhcacheShiro named [{}]", name);
      } else {
        log.info("Using existing EhcacheShiro named [{}]", name);
      }

      return new EhcacheShiro<K, V>(cache);
    } catch (IOException e) {
      throw new CacheException(e);
    }
  }

  private org.ehcache.Cache<Object, Object> createCache(String name) {
    try {
      XmlConfiguration xmlConfiguration = getConfiguration();
      CacheConfigurationBuilder<Object, Object> configurationBuilder = xmlConfiguration.newCacheConfigurationBuilderFromTemplate(
              "defaultCacheConfiguration", Object.class, Object.class);
      CacheConfiguration<Object, Object> cacheConfiguration = configurationBuilder.build();
      return ensureCacheManager().createCache(name, cacheConfiguration);
    } catch (ReflectiveOperationException | IOException e) {
      throw new CacheException(e);
    }
  }

  private org.ehcache.CacheManager ensureCacheManager() throws IOException {
    if (manager == null) {
      manager = CacheManagerBuilder.newCacheManager(getConfiguration());
      manager.init();

      cacheManagerImplicitlyCreated = true;
    }

    return manager;
  }

  private URL getResource() throws IOException {
    String path = getCacheManagerConfigFile();
    String classpathRoot = ResourceUtils.CLASSPATH_PREFIX + "/";
    if (path.startsWith(classpathRoot)) {
      path = ResourceUtils.CLASSPATH_PREFIX + path.substring(classpathRoot.length());
    }
    URL resource = ResourceUtils.getURLForPath(path);
    if (Objects.isNull(resource)) {
      throw new FileNotFoundException(path);
    }
    return resource;
  }

  private XmlConfiguration getConfiguration() throws IOException {
    if (cacheConfiguration == null) {
      cacheConfiguration = new XmlConfiguration(getResource());
    }

    return cacheConfiguration;
  }

  public synchronized void destroy() throws Exception {
    if (cacheManagerImplicitlyCreated && manager != null) {
      manager.close();
      manager = null;
    }
  }

  /**
   * Initializes this instance.
   * <P>
   * If a {@link #setCacheManager CacheManager} has been
   * explicitly set (e.g. via Dependency Injection or programatically) prior to calling this
   * method, this method does nothing.
   * </P>
   * <P>
   * However, if no {@code CacheManager} has been set a new {@link org.ehcache.Cache} will be initialized.
   * It will use {@code ehcache.xml} configuration file at the root of the classpath.
   * </P>
   *
   * @throws org.apache.shiro.cache.CacheException if there are any CacheExceptions thrown by EhCache.
   */
  public synchronized void init() throws ShiroException {
    try {
      ensureCacheManager();
    } catch (IOException e) {
      throw new ShiroException(e);
    }
  }
}
