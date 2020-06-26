package org.commonjava.indy.pkg.pypi.content;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.AbstractTransferDecorator;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.util.IdempotentCloseOutputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.OutputStream;

import static org.commonjava.indy.model.core.StoreType.remote;
import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_PKG_KEY;
import static org.commonjava.maven.galley.util.PathUtils.normalize;

@ApplicationScoped
@Named
public class PyPIListingTransferDecorator
                extends AbstractTransferDecorator
{
    @Inject
    private CDNRedirectionDatabase cdn;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Override
    public OutputStream decorateWrite( OutputStream stream, Transfer transfer, TransferOperation op,
                                       EventMetadata metadata ) throws IOException
    {
        Location loc = transfer.getLocation();
        if ( !( loc instanceof KeyedLocation ) )
        {
            logger.trace( "Not a keyed location; no decoration needed." );
            return stream;
        }

        KeyedLocation keyedLocation = (KeyedLocation) loc;
        if ( !( PYPI_PKG_KEY.equals( keyedLocation.getKey().getPackageType() ) ) )
        {
            logger.debug( "Not a PyPI repository; no decoration needed." );
            return stream;
        }

        logger.debug( "PyPI HTML parser for decorateWrite, transfer: {}", transfer );

        UrlGeneratorFunction urlGenerator = (UrlGeneratorFunction) metadata.get( UrlGeneratorFunction.KEY );

        if ( urlGenerator == null || remote != keyedLocation.getKey().getType()
                        || (transfer.getFullPath().indexOf( '.' ) > 0 && !transfer.getPath().endsWith( "/" ) ) )
        {
            logger.debug( "Not a PyPI directory location; no decoration needed." );
            return stream;
        }

        logger.debug( "PyPI directory content listing will be parsed and recorded in PyPI CDN layer for: {}", transfer );
        return new ListingParserStream( stream, keyedLocation.getKey(), transfer.getPath(), cdn, urlGenerator );
    }

    private static class ListingParserStream
                    extends IdempotentCloseOutputStream
    {
        private static final String TIMER = "io.maven.metadata.out.filter";

        private final Logger logger = LoggerFactory.getLogger( this.getClass() );

        private StringBuilder buffer = new StringBuilder();

        private OutputStream stream;

        private StoreKey storeKey;

        private String basePath;

        private CDNRedirectionDatabase cdn;

        private UrlGeneratorFunction urlGenerator;

        private boolean lastFlush = false;

        private ListingParserStream( final OutputStream stream, StoreKey storeKey, String basePath, CDNRedirectionDatabase cdn,
                                     UrlGeneratorFunction urlGenerator )
        {
            super( stream );
            this.stream = stream;
            this.storeKey = storeKey;
            this.basePath = basePath;
            this.cdn = cdn;
            this.urlGenerator = urlGenerator;
        }

        @Override
        public void write( final int b )
        {
            buffer.append( (char) b );
        }

        @Override
        public void write( byte[] b, int off, int len ) throws IOException
        {
            for(int i=off; i<len; i++){
                buffer.append( (char) b[i] );
            }
        }

        @Override
        public void write( byte[] b ) throws IOException
        {
            for(int i=0; i<b.length; i++){
                buffer.append( (char) b[i] );
            }
        }

        @Override
        public void flush() throws IOException
        {
            if ( !lastFlush )
            {
                return;
            }

            lastFlush = false;
            try
            {
                stream.write( parseAndReplace().getBytes() );
                stream.flush();
            }
            finally
            {
                buffer.setLength( 0 );
            }
//            super.close();
        }

        @Override
        public void close() throws IOException
        {
            lastFlush = true;
            flush();
            super.close();
        }

        private String parseAndReplace()
        {
            logger.debug( "Parsing HTML from buffer of length: {}", buffer.length() );
            Document doc = Jsoup.parse( buffer.toString() );
            doc.select( "a" ).forEach( elt->{
                String href = elt.attr( "href" );
                String fname = elt.text();
                String localPath = normalize( basePath, fname );
                String localHref = urlGenerator.generate( localPath );

                logger.debug( "Found link:\n    File: {}\n    CDN href: {}\n    Local href: {}", fname, href, localHref );
                if ( !href.startsWith( "/" ) )
                {
                    cdn.register( storeKey, localPath, href );
                }

                elt.attr( "href", localHref );
            } );

            String html = doc.outerHtml();

            logger.info( "Replacement HTML for PyPI listing:\n\n{}", html );

            return html;
        }
    }
}
