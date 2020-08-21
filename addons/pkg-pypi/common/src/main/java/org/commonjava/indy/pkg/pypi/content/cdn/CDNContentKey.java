package org.commonjava.indy.pkg.pypi.content.cdn;

import org.commonjava.indy.model.core.StoreKey;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Objects;

public class CDNContentKey
                implements Externalizable
{
    private static final int VERSION = 1;

    private StoreKey key;
    private String path;

    CDNContentKey(){}

    public CDNContentKey( StoreKey key, String path )
    {
        this.key = key;
        this.path = path;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
            return true;
        if ( !( o instanceof CDNContentKey ) )
            return false;
        CDNContentKey that = (CDNContentKey) o;
        return key.equals( that.key ) && path.equals( that.path );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( key, path );
    }

    @Override
    public void writeExternal( ObjectOutput out ) throws IOException
    {
        out.writeInt( VERSION );
        out.writeObject( key );
        out.writeObject( path );
    }

    @Override
    public void readExternal( ObjectInput in ) throws IOException, ClassNotFoundException
    {
        int version = in.readInt();
        if ( version > VERSION )
        {
            throw new IOException( "Cannot deserialize " + CDNContentKey.class.getSimpleName() + " object. Object version is: " + version
                                                   + ", but class version is only: " + VERSION );
        }

        key = (StoreKey) in.readObject();
        path = (String) in.readObject();
    }
}
