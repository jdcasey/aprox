package org.commonjava.indy.content;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simple envelope to control serialization of a set of {@link ProjectRelationship}, with a header referencing the
 * Maven project (GAV) from which it was parsed.
 */
public class RelationshipSet
        implements Externalizable
{
    private static final int SERIAL_VERSION = 1;

    private ProjectVersionRef project;

    private Map<ProjectVersionRef, StoreKey> pomSources;

    private Set<ProjectRelationship<?, ?>> relationships;

    public RelationshipSet(){}

    public RelationshipSet( ProjectVersionRef project, Map<ProjectVersionRef, StoreKey> pomSources, Set<ProjectRelationship<?, ?>> relationships )
    {
        // just make sure this isn't a subclass.
        this.project = project.asProjectVersionRef();
        this.pomSources = pomSources;
        this.relationships = relationships;
    }

    public static int getSerialVersion()
    {
        return SERIAL_VERSION;
    }

    public ProjectVersionRef getProject()
    {
        return project;
    }

    public Map<ProjectVersionRef, StoreKey> getPomSources()
    {
        return pomSources;
    }

    public Set<ProjectRelationship<?, ?>> getRelationships()
    {
        return relationships;
    }

    @Override
    public void writeExternal( ObjectOutput out )
            throws IOException
    {
        out.writeInt( SERIAL_VERSION );
        out.writeObject( project );
        out.writeObject( pomSources );
        out.writeObject( relationships );
    }

    @Override
    public void readExternal( ObjectInput in )
            throws IOException, ClassNotFoundException
    {
        int ver = in.readInt();
        if ( ver > SERIAL_VERSION )
        {
            throw new IOException(
                    "Cannot deserialize newer version of " + getClass().getSimpleName() + " (class version: "
                            + SERIAL_VERSION + ", serialized version: " + ver );
        }

        project = (ProjectVersionRef) in.readObject();
        pomSources = (Map<ProjectVersionRef, StoreKey>) in.readObject();
        relationships = (Set<ProjectRelationship<?, ?>>) in.readObject();
    }
}
