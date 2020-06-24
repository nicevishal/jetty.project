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

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionContext;
import org.infinispan.Cache;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toSet;

public class EmbeddedQueryManager implements QueryManager
{
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedQueryManager.class);
    
    private Cache<String, InfinispanSessionData> _cache;

    public EmbeddedQueryManager(Cache<String, InfinispanSessionData> cache)
    {
        _cache = cache;
    }

    @Override
    public Set<String> queryExpiredSessions(SessionContext sessionContext, long time)
    {
        Objects.requireNonNull(sessionContext);
        QueryFactory qf = Search.getQueryFactory(_cache);
        Query q = qf.from(InfinispanSessionData.class)
            .select("id")
            .having("contextPath").eq(sessionContext.getCanonicalContextPath())
            .and()
            .having("expiry").lte(time)
            .and().having("expiry").gt(0)
            .build();

        List<Object[]> list = q.list();
        Set<String> ids = list.stream().map(a -> (String)a[0]).collect(toSet());
        return ids;
    }

    public void deleteOrphanSessions(long time)
    {
        QueryFactory qf = Search.getQueryFactory(_cache);
        Query q = qf.from(InfinispanSessionData.class)
            .select("id", "contextPath", "vhost")
            .having("expiry").lte(time)
            .and().having("expiry").gt(0)
            .build();
        List<Object[]> list = q.list();
        list.stream().forEach(a ->
        {
            String key = InfinispanKeyBuilder.build((String)a[1], (String)a[2], (String)a[0]);
            try
            {
                _cache.remove(key);
            }
            catch (Exception e)
            {
                LOG.warn("Error deleting {}", key, e);
            }
        });
    }
}
