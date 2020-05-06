package org.commonjava.indy.model.core;

import io.swagger.annotations.ApiModel;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Objects;
import java.util.Set;

@ApiModel( description = "Request of batch files that will be removed from the repository. Specifying the folo"
                + " trackingID for removing the files uploaded through it, and that's supported in folo client only." )
public class BatchDeleteRequest
    implements Externalizable
{

    private static final int BATCH_DELETE_REQUEST_VERSION = 1;

    private StoreKey storeKey;

    private String trackingID;

    private Set<String> paths;

    public String getTrackingID()
    {
        return trackingID;
    }

    public void setTrackingID( String trackingID )
    {
        this.trackingID = trackingID;
    }

    public StoreKey getStoreKey()
    {
        return storeKey;
    }

    public void setStoreKey( StoreKey storeKey )
    {
        this.storeKey = storeKey;
    }

    public Set<String> getPaths()
    {
        return paths;
    }

    public void setPaths( Set<String> paths )
    {
        this.paths = paths;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
            return true;
        if ( o == null || getClass() != o.getClass() )
            return false;
        BatchDeleteRequest that = (BatchDeleteRequest) o;
        return Objects.equals( storeKey, that.storeKey ) && Objects.equals( trackingID, that.trackingID )
                        && Objects.equals( paths, that.paths );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( storeKey, trackingID, paths );
    }

    @Override
    public String toString()
    {
        return "BatchDeleteRequest{" + "storeKey=" + storeKey + ", trackingID='" + trackingID + '\'' + ", paths=" + paths
                        + '}';
    }

    @Override
    public void writeExternal( final ObjectOutput out ) throws IOException
    {
        out.writeObject( BATCH_DELETE_REQUEST_VERSION );
        out.writeObject( storeKey );
        out.writeObject( paths );
        out.writeObject( trackingID );
    }

    @Override
    public void readExternal( final ObjectInput in ) throws IOException, ClassNotFoundException
    {
        int batchDeleteRequestVersion = in.readInt();
        if ( batchDeleteRequestVersion > BATCH_DELETE_REQUEST_VERSION )
        {
            throw new IOException( "Cannot deserialize. BatchDeleteRequest version in data stream is: " + batchDeleteRequestVersion
                                                   + " but this class can only deserialize up to version: " + BATCH_DELETE_REQUEST_VERSION );
        }

        this.storeKey = (StoreKey) in.readObject();
        this.paths = (Set<String>) in.readObject();
        this.trackingID = (String) in.readObject();
    }
}
