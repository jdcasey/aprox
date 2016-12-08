package org.commonjava.indy.content;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;

/**
 * Created by jdcasey on 12/7/16.
 */
public class RelationshipSetTest
{
    @Test
    public void roundTripSerializeEmptySet()
            throws IOException, ClassNotFoundException
    {
        RelationshipSet src =
                new RelationshipSet( new SimpleProjectVersionRef( "org.foo", "bar", "1.0" ), Collections.emptyMap(),
                                     Collections.emptySet() );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream( baos );
        out.writeObject( src );

        ObjectInputStream in = new ObjectInputStream( new ByteArrayInputStream( baos.toByteArray() ) );
        RelationshipSet result = (RelationshipSet) in.readObject();
    }
}
