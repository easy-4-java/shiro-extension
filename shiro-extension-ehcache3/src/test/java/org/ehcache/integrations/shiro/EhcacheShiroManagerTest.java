package org.ehcache.integrations.shiro;

import java.time.Duration;
import java.util.List;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheException;
import org.apache.shiro.lang.ShiroException;
import org.ehcache.Status;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.xml.XmlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EhcacheShiroManagerTest {

    @Test
    void usesTheConfiguredExpiryForExistingAndNewCaches() throws Exception {
        var manager = new EhcacheShiroManager();
        manager.setCacheManagerConfigFile("classpath:/ehcache-shiro-test.xml");
        try {
            manager.init();
            Cache<String, String> expired = manager.getCache("short-lived");
            expired.put("user", "session");
            var expiry = manager.getCacheManager().getCache("short-lived", Object.class, Object.class)
                    .getRuntimeConfiguration().getExpiryPolicy();
            assertEquals(Duration.ofSeconds(1), expiry.getExpiryForCreation("user", "session"));
            Thread.sleep(Duration.ofMillis(1_050));
            assertNull(expired.get("user"));

            Cache<String, String> active = manager.getCache("new-cache");
            active.put("user", "session");
            assertEquals("session", manager.getCache("new-cache").get("user"));
            var runtime = manager.getCacheManager().getCache("new-cache", Object.class, Object.class)
                    .getRuntimeConfiguration();
            assertEquals(Duration.ofSeconds(37), runtime.getExpiryPolicy().getExpiryForCreation("user", "session"));
        } finally {
            manager.destroy();
        }
        assertNull(manager.getCacheManager());
    }

    @Test
    void usesTypedExistingRegionsWithoutOwningTheInjectedManager() throws Exception {
        try (var nativeManager = CacheManagerBuilder.newCacheManagerBuilder()
                .withCache("sessions", CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class, String.class, ResourcePoolsBuilder.heap(10)))
                .build(true)) {
            var manager = new EhcacheShiroManager();
            manager.setCacheManager(nativeManager);
            manager.init();
            assertSame(nativeManager, manager.getCacheManager());
            Cache<String, String> cache = manager.getCache("sessions");
            assertTrue(cache.keys().isEmpty());
            assertTrue(cache.values().isEmpty());
            assertNull(cache.get(null));
            assertNull(cache.remove("missing"));
            assertNull(cache.put("account", "previous"));
            assertEquals("previous", cache.put("account", "current"));
            assertEquals("current", nativeManager.getCache("sessions", String.class, String.class).get("account"));
            var keys = cache.keys().iterator();
            assertTrue(keys.hasNext());
            assertEquals("account", keys.next());
            assertFalse(keys.hasNext());
            var values = cache.values().iterator();
            assertTrue(values.hasNext());
            assertEquals("current", values.next());
            assertFalse(values.hasNext());
            assertEquals(1, cache.keys().size());
            assertEquals(1, cache.values().size());
            assertFalse(cache.keys().isEmpty());
            assertFalse(cache.values().isEmpty());
            assertReadOnly(cache);
            assertEquals("current", cache.remove("account"));
            cache.put("another-account", "session");
            cache.clear();
            assertEquals(0, cache.size());

            manager.destroy();
            assertEquals(Status.AVAILABLE, nativeManager.getStatus());
        }
    }

    @Test
    void readsTheBundledDefaultConfigurationAndReportsMissingFiles() throws Exception {
        var manager = new EhcacheShiroManager();
        var xml = new XmlConfiguration(EhcacheShiroManager.class.getResource("ehcache.xml"));
        assertTrue(xml.getCacheConfigurations().containsKey("shiro-activeSessionCache"));
        var policy = xml.newCacheConfigurationBuilderFromTemplate("defaultCacheConfiguration", Object.class, Object.class)
                .build().getExpiryPolicy();
        assertEquals(Duration.ofSeconds(120), policy.getExpiryForCreation("user", "session"));

        manager.setCacheManagerConfigFile("classpath:missing-ehcache.xml");
        assertThrows(ShiroException.class, manager::init);
        assertThrows(CacheException.class, () -> manager.getCache("sessions"));
        assertThrows(IllegalArgumentException.class, () -> new EhcacheShiro<>(null));
    }

    private static void assertReadOnly(Cache<String, String> cache) {
        var keys = cache.keys();
        assertThrows(UnsupportedOperationException.class, () -> keys.addAll(List.of("account")));
        assertThrows(UnsupportedOperationException.class, () -> keys.remove("account"));
        assertThrows(UnsupportedOperationException.class, () -> keys.removeAll(List.of("account")));
        assertThrows(UnsupportedOperationException.class, () -> keys.retainAll(List.of("account")));
        var values = cache.values();
        assertThrows(UnsupportedOperationException.class, () -> values.addAll(List.of("current")));
        assertThrows(UnsupportedOperationException.class, () -> values.remove("current"));
        assertThrows(UnsupportedOperationException.class, () -> values.removeAll(List.of("current")));
        assertThrows(UnsupportedOperationException.class, () -> values.retainAll(List.of("current")));
        assertThrows(UnsupportedOperationException.class, () -> keys.iterator().remove());
    }
}
