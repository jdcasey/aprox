package org.commonjava.indy.koji.content;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import org.commonjava.indy.model.core.RemoteRepository;

import java.util.List;

public final class KojiBuildInfoWithRemote
{
    private final KojiBuildInfo build;

    private final List<KojiArchiveInfo> archives;

    private final RemoteRepository remoteRepository;

    public KojiBuildInfoWithRemote( final KojiBuildInfo build, final List<KojiArchiveInfo> archives,
                                    final RemoteRepository remoteRepository )
    {
        this.build = build;
        this.archives = archives;
        this.remoteRepository = remoteRepository;
    }

    public KojiBuildInfo getBuild()
    {
        return build;
    }

    public List<KojiArchiveInfo> getArchives()
    {
        return archives;
    }

    public RemoteRepository getRemoteRepository()
    {
        return remoteRepository;
    }
}
