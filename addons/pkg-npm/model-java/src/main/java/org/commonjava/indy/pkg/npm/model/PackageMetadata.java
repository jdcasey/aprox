/**
 * Copyright (C) 2011-2020 Red Hat, Inc. (https://github.com/Commonjava/indy)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.pkg.npm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.commonjava.atlas.maven.ident.util.VersionUtils;
import org.commonjava.atlas.maven.ident.version.SingleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApiModel( description = "Specify the metadata of the relevant pulling package info." )
@JsonIgnoreProperties( { "_id", "_rev", "_attachments" } )
public class PackageMetadata
                implements Serializable, Comparable<PackageMetadata>
{
    private static final long serialVersionUID = 1L;

    private static final String TIMEOUT_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    private static final String MODIFIED = "modified";

    private static final String CREATED = "created";

    @ApiModelProperty( required = true, dataType = "String", value = "The name is what your thing is called." )
    private String name;

    private String description;

    @JsonProperty( "dist-tags" )
    private DistTag distTags = new DistTag();

    private Map<String, VersionMetadata> versions = new LinkedHashMap<String, VersionMetadata>();

    private List<UserInfo> maintainers = new ArrayList<>();

    private Map<String, String> time = new LinkedHashMap<String, String>();

    private UserInfo author;

    private Map<String, Boolean> users = new LinkedHashMap<String, Boolean>();

    @ApiModelProperty( required = false, dataType = "Repository", value = "Specify the place where your code lives." )
    private Repository repository;

    private String readme;

    private String readmeFilename;

    private String homepage;

    private List<String> keywords = new ArrayList<>();

    @ApiModelProperty( required = false, dataType = "Bugs", value = "The issue tracker and / or the email address to which issues should be reported." )
    private Bugs bugs;

    private License license;

    public PackageMetadata()
    {
    }

    public PackageMetadata( String name )
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    public void setName( String name )
    {
        this.name = name;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription( String description )
    {
        this.description = description;
    }

    public DistTag getDistTags()
    {
        return distTags;
    }

    public void setDistTags( DistTag distTags )
    {
        this.distTags = distTags;
    }

    public Map<String, VersionMetadata> getVersions()
    {
        return versions;
    }

    public void setVersions( Map<String, VersionMetadata> versions )
    {
        this.versions = versions;
    }

    public List<UserInfo> getMaintainers()
    {
        return maintainers;
    }

    public void setMaintainers( List<UserInfo> maintainers )
    {
        this.maintainers = maintainers;
    }

    public Map<String, String> getTime()
    {
        return time;
    }

    public void setTime( Map<String, String> time )
    {
        this.time = time;
    }

    public UserInfo getAuthor()
    {
        return author;
    }

    public void setAuthor( UserInfo author )
    {
        this.author = author;
    }

    public Map<String, Boolean> getUsers()
    {
        return users;
    }

    public void setUsers( Map<String, Boolean> users )
    {
        this.users = users;
    }

    public Repository getRepository()
    {
        return repository;
    }

    public void setRepository( Repository repository )
    {
        this.repository = repository;
    }

    public String getReadme()
    {
        return readme;
    }

    public void setReadme( String readme )
    {
        this.readme = readme;
    }

    public String getReadmeFilename()
    {
        return readmeFilename;
    }

    public void setReadmeFilename( String readmeFilename )
    {
        this.readmeFilename = readmeFilename;
    }

    public String getHomepage()
    {
        return homepage;
    }

    public void setHomepage( String homepage )
    {
        this.homepage = homepage;
    }

    public List<String> getKeywords()
    {
        return keywords;
    }

    public void setKeywords( List<String> keywords )
    {
        this.keywords = keywords;
    }

    public Bugs getBugs()
    {
        return bugs;
    }

    public void setBugs( Bugs bugs )
    {
        this.bugs = bugs;
    }

    public License getLicense()
    {
        return license;
    }

    public void setLicense( License license )
    {
        this.license = license;
    }

    public void addMaintainers( UserInfo userInfo )
    {
        this.getMaintainers().add( userInfo );
    }

    public void addKeywords( String key )
    {
        this.getKeywords().add( key );
    }

    @Override
    public int compareTo( PackageMetadata o )
    {
        return 0;
    }

    public boolean merge( PackageMetadata source, boolean isForGroup )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );

        boolean changed = false;
        if ( source.getName() != null )
        {
            this.setName( source.getName() );
            changed = true;
        }
        if ( source.getDescription() != null )
        {
            this.setDescription( source.getDescription() );
            changed = true;
        }
        if ( source.getAuthor() != null )
        {
            this.setAuthor( source.getAuthor() );
            changed = true;
        }
        if ( source.getRepository() != null )
        {
            this.setRepository( source.getRepository() );
            changed = true;
        }
        if ( source.getReadme() != null )
        {
            this.setReadme( source.getReadme() );
            changed = true;
        }
        if ( source.getReadmeFilename() != null )
        {
            this.setReadmeFilename( source.getReadmeFilename() );
            changed = true;
        }
        if ( source.getHomepage() != null )
        {
            this.setHomepage( source.getHomepage() );
            changed = true;
        }
        if ( source.getBugs() != null )
        {
            this.setBugs( source.getBugs() );
            changed = true;
        }
        if ( source.getLicense() != null )
        {
            this.setLicense( source.getLicense() );
            changed = true;
        }

        // merge maintainers list
        Iterator maintainer = source.getMaintainers().iterator();
        while ( maintainer.hasNext() )
        {
            UserInfo m = (UserInfo) maintainer.next();
            if ( m.getName() != null && !maintainers.contains( m ) )
            {
                this.addMaintainers( new UserInfo( m.getName(), m.getEmail(), m.getUrl() ) );
                changed = true;
            }
        }

        // merge keywords list
        Iterator keyword = source.getKeywords().iterator();
        while ( keyword.hasNext() )
        {
            String key = (String) keyword.next();
            if ( !keywords.contains( key ) )
            {
                this.addKeywords( key );
                changed = true;
            }
        }

        // merge users map
        Map<String, Boolean> sourceUsers = source.getUsers();
        for ( final String user : sourceUsers.keySet() )
        {
            if ( users.containsKey( user ) && users.get( user ).equals( sourceUsers.get( user ) ) )
            {
                continue;
            }
            users.put( user, sourceUsers.get( user ) );
            changed = true;
        }

        // merge time map
        SimpleDateFormat sdf = new SimpleDateFormat( TIMEOUT_FORMAT );
        Map<String, Date> clone = new LinkedHashMap<>();

        for ( final String key : time.keySet() )
        {
            String value = time.get( key );
            Date date = null;
            try
            {
                date = sdf.parse( value );
            }
            catch ( ParseException e )
            {
                logger.error( String.format( "Cannot parse date: %s. Reason: %s", value, e ) );
            }
            clone.put( key, date );
        }

        Map<String, String> sourceTimes = source.getTime();
        boolean added = false;
        for ( final String key : sourceTimes.keySet() )
        {
            String value = sourceTimes.get( key );
            Date date = null;
            try
            {
                date = sdf.parse( value );
            }
            catch ( ParseException e )
            {
                logger.error( String.format( "Cannot parse date: %s. Reason: %s", value, e ) );
            }
            // if source's version update time is more recent(sort as the letter order), will update it into the original map.
            if ( clone.containsKey( key ) && date.compareTo( clone.get( key ) ) <= 0 )
            {
                continue;
            }
            clone.put( key, date );
            added = true;
            changed = true;
        }

        // only sorting the map when update occurred
        if ( added )
        {
            // sort as the time value in map
            List<Map.Entry<String, Date>> timeList = new ArrayList<>( clone.entrySet() );
            // sort the time as value (update time) asc
            Collections.sort( timeList, ( o1, o2 ) -> o1.getValue().compareTo( o2.getValue() ) );

            Map<String, String> result = new LinkedHashMap<>();
            // make the 'modified' and 'created' value as the first two keys in final map
            if ( clone.get( MODIFIED ) != null )
            {
                result.put( MODIFIED, sdf.format( clone.get( MODIFIED ) ) );
            }
            if ( clone.get( CREATED ) != null )
            {
                result.put( CREATED, sdf.format( clone.get( CREATED ) ) );
            }

            for ( final Map.Entry<String, Date> entry : timeList )
            {
                if ( MODIFIED.equals( entry.getKey() ) || CREATED.equals( entry.getKey() ) )
                {
                    continue;
                }
                result.put( entry.getKey(), sdf.format( entry.getValue() ) );
            }
            time = result;
        }

        // merge dist-tag object
        DistTag sourceDist = source.getDistTags();
        Map<String, String> sourceDistMap = sourceDist.fetchTagsMap();
        Map<String, String> thisDistMap = distTags.fetchTagsMap();
        if ( thisDistMap.isEmpty() && !sourceDistMap.isEmpty() )
        {
            this.setDistTags( sourceDist );
            changed = true;
        }
        else
        {
            for ( final String tag : sourceDistMap.keySet() )
            {
                SingleVersion sourceVersion = VersionUtils.createSingleVersion( sourceDistMap.get( tag ) );
                SingleVersion thisVersion = VersionUtils.createSingleVersion( thisDistMap.get( tag ) );
                if ( thisDistMap.containsKey( tag ) && sourceVersion.compareTo( thisVersion ) <= 0 )
                {
                    continue;
                }
                distTags.putTag( tag, sourceDistMap.get( tag ) );
                changed = true;
            }
        }

        //merge versions, keep the first version metadata for a given version that coming across
        Map<String, VersionMetadata> sourceVersions = source.getVersions();
        for ( final String v : sourceVersions.keySet() )
        {
            VersionMetadata value = sourceVersions.get( v );
            // if the versions meet are same here:
            // for group merging, versions only accepts the first one coming to the group,
            // for publish merging, it always accepts the latest updated version meta
            if ( !isForGroup || !versions.containsKey( v ) )
            {
                versions.put( v, value );
                changed = true;
            }
        }

        return changed;
    }
}
