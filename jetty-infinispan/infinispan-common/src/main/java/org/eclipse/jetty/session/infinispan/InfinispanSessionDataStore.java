//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session.infinispan;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.infinispan.commons.api.BasicCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InfinispanSessionDataStore
 *
 *
 */
@ManagedObject
public class InfinispanSessionDataStore extends AbstractSessionDataStore
{
    private static final Logger LOG = LoggerFactory.getLogger(InfinispanSessionDataStore.class);

    /**
     * Clustered cache of sessions
     */
    private BasicCache<String, InfinispanSessionData> _cache;
    private int _infinispanIdleTimeoutSec;
    private QueryManager _queryManager;
    private boolean _passivating;
    
    /**
     * Get the clustered cache instance.
     * 
     * @return the cache
     */
    public BasicCache<String, InfinispanSessionData> getCache() 
    {
        return _cache;
    }
    
    /**
     * Set the clustered cache instance.
     * 
     * @param cache the cache
     */
    public void setCache(BasicCache<String, InfinispanSessionData> cache) 
    {
        this._cache = cache;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        if (_cache == null)
            throw new IllegalStateException("No cache");

        try 
        {
            _passivating = false;
            Class<?> remoteClass = InfinispanSessionDataStore.class.getClassLoader().loadClass("org.infinispan.client.hotrod.RemoteCache");
            if (remoteClass.isAssignableFrom(_cache.getClass()))
                _passivating = true;
        }
        catch (ClassNotFoundException e)
        {
            //expected if not running with remote cache
            LOG.info("Hotrod classes not found, assuming infinispan in embedded mode");
        }
    }

    public QueryManager getQueryManager()
    {
        return _queryManager;
    }

    public void setQueryManager(QueryManager queryManager)
    {
        _queryManager = queryManager;
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {  
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Loading session {} from infinispan", id);

            InfinispanSessionData sd = _cache.get(getCacheKey(id));
            if (isPassivating() && sd != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Deserializing session attributes for {}", id);
                sd.deserializeAttributes();
            }

            return sd;
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Deleting session with id {} from infinispan", id);
        return (_cache.remove(getCacheKey(id)) != null);
    }

    @Override
    public Set<String> doGetExpired(Set<String> candidates, long time)
    {
        if (candidates == null  || candidates.isEmpty())
            return candidates;

        Set<String> expired = new HashSet<>();
   
        for (String candidate:candidates)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Checking expiry for candidate {}", candidate);
            try
            {
                SessionData sd = load(candidate);

                //if the session no longer exists
                if (sd == null)
                {
                    expired.add(candidate);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} does not exist in infinispan", candidate);
                }
                else
                {
                    if (_context.getWorkerName().equals(sd.getLastNode()))
                    {
                        //we are its manager, add it to the expired set if it is expired now
                        if ((sd.getExpiry() > 0) && sd.getExpiry() <= time)
                        {
                            expired.add(candidate);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Session {} managed by {} is expired", candidate, _context.getWorkerName());
                        }
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn("Error checking if candidate {} is expired", candidate, e);
            }
        }
        return expired;
    }
    
    @Override
    public Set<String> doGetExpired(long time)
    {
        //If there is a query manager, find the sessions for our context that expired before the time limit
        if (_queryManager != null)
        {
            Set<String> expired = new HashSet<>();
            for (String sessionId : _queryManager.queryExpiredSessions(_context, time))
            {
                expired.add(sessionId);
                if (LOG.isDebugEnabled())
                    LOG.debug("{}- Found expired sessionId={}", _context.getWorkerName(), sessionId);
            }
            return expired;
        }
        return Collections.emptySet();
    }

    @Override
    public void doCleanOrphans(long timeLimit)
    {
        //if there is a query manager, find the sessions for any context that expired before the time limit and delete
        if (_queryManager != null)
            _queryManager.deleteOrphanSessions(timeLimit);
        else
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to clean orphans, no QueryManager");
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        //Put an idle timeout on the cache entry if the session is not immortal - 
        //if no requests arrive at any node before this timeout occurs, or no node 
        //scavenges the session before this timeout occurs, the session will be removed.
        //NOTE: that no session listeners can be called for this.
        if (data.getMaxInactiveMs() > 0 && getInfinispanIdleTimeoutSec() > 0)
            _cache.put(getCacheKey(id), (InfinispanSessionData)data, -1, TimeUnit.MILLISECONDS, getInfinispanIdleTimeoutSec(), TimeUnit.SECONDS);
        else
            _cache.put(getCacheKey(id), (InfinispanSessionData)data);

        if (LOG.isDebugEnabled())
            LOG.debug("Session {} saved to infinispan, expires {} ", id, data.getExpiry());
    }
    
    public String getCacheKey(String id)
    {
        return InfinispanKeyBuilder.build(_context.getCanonicalContextPath(), _context.getVhost(), id);
    }

    @ManagedAttribute(value = "does store serialize sessions", readonly = true)
    @Override
    public boolean isPassivating()
    {
        return _passivating;
    }
    
    @Override
    public boolean exists(String id) throws Exception
    {
        // TODO find a better way to do this that does not pull into memory the
        // whole session object
        final AtomicBoolean reference = new AtomicBoolean();
        final AtomicReference<Exception> exception = new AtomicReference<>();

        Runnable load = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    SessionData sd = load(id);
                    if (sd == null)
                    {
                        reference.set(false);
                        return;
                    }

                    if (sd.getExpiry() <= 0)
                        reference.set(true); //never expires
                    else
                        reference.set(sd.getExpiry() > System.currentTimeMillis()); //not expired yet
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        
        //ensure the load runs in the context classloader scope
        _context.run(load);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new InfinispanSessionData(id, _context.getCanonicalContextPath(), _context.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }

    /**
     * @param sec the infinispan-specific idle timeout in sec or 0 if not set
     */
    public void setInfinispanIdleTimeoutSec(int sec)
    {
        _infinispanIdleTimeoutSec = sec;
    } 
    
    @ManagedAttribute(value = "infinispan idle timeout sec", readonly = true)
    public int getInfinispanIdleTimeoutSec()
    {
        return _infinispanIdleTimeoutSec;
    }

    @Override
    public String toString()
    {
        return String.format("%s[cache=%s,idleTimeoutSec=%d]", super.toString(), (_cache == null ? "" : _cache.getName()), _infinispanIdleTimeoutSec);
    }
}
