package org.commonjava.indy.model.core;

import io.swagger.annotations.ApiModel;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Objects;
import java.util.Set;

@ApiModel( description = "Request of batch files that will be removed from the repository." )
public class DemoteRequest
    implements Externalizable
{

    private static final int DEMOTE_REQUEST_VERSION = 1;


    private StoreKey storeKey;

    private Set<String> paths;

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
        DemoteRequest that = (DemoteRequest) o;
        return Objects.equals( storeKey, that.storeKey ) && Objects.equals( paths, that.paths );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( storeKey, paths );
    }

    @Override
    public String toString()
    {
        return "DemoteRequest{" + "storeKey=" + storeKey + ", paths=" + paths + '}';
    }

    @Override
    public void writeExternal( final ObjectOutput out ) throws IOException
    {
        out.writeObject( DEMOTE_REQUEST_VERSION );
        out.writeObject( storeKey );
        out.writeObject( paths );
    }

    @Override
    public void readExternal( final ObjectInput in ) throws IOException, ClassNotFoundException
    {
        int demoteRequestVersion = in.readInt();
        if ( demoteRequestVersion > DEMOTE_REQUEST_VERSION )
        {
            throw new IOException( "Cannot deserialize. DemoteRequest version in data stream is: " + demoteRequestVersion
                                                   + " but this class can only deserialize up to version: " + DEMOTE_REQUEST_VERSION );
        }

        this.storeKey = (StoreKey) in.readObject();
        this.paths = (Set<String>) in.readObject();
    }
}
